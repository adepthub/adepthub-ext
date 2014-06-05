package adept.sbt.commands

import sbt.State
import java.io.File
import adept.AdeptHub
import adept.ivy.scalaspecific.ScalaBinaryVersionConverter
import adept.resolution.models._
import adepthub.models._
import adept.repository.metadata._
import adept.repository.models._
import adept.ext.models.Module
import adept.ext.VersionRank
import adept.ivy.IvyUtils
import adept.lockfile.Lockfile
import adept.lockfile.InternalLockfileWrapper
import adept.sbt.AdeptDefaults
import adept.ext.AttributeDefaults
import adept.sbt.SbtUtils
import adept.sbt.AdeptKeys
import adept.sbt.AdeptSbtUtils
import scala.util.Success
import scala.util.Failure
import adept.sbt.UserInputException
import adept.sbt.AdeptSbtUtils
import adept.sbt.UserInputException

object InstallCommand {
  import sbt.complete.DefaultParsers._
  import sbt.complete._

  def using(scalaBinaryVersion: String, majorJavaVersion: Int, minorJavaVersion: Int, confs: Set[String],  ivyConfigurations: Seq[sbt.Configuration], lockfileGetter: String => File, adepthub: AdeptHub) = {
    ((token("install") ~> (Space ~> NotSpaceClass.+).+).map { args =>
      new InstallCommand(args.map(_.mkString), scalaBinaryVersion, majorJavaVersion, minorJavaVersion, confs, ivyConfigurations, lockfileGetter, adepthub)
    })

  }
}

class InstallCommand(args: Seq[String], scalaBinaryVersion: String, majorJavaVersion: Int, minorJavaVersion: Int, confs: Set[String], ivyConfigurations: Seq[sbt.Configuration], lockfileGetter: String => File, adepthub: AdeptHub) extends AdeptCommand {
  def execute(state: State): State = {
    val logger = state.globalLogging.full
    val defaultConf = "compile" //TODO: setting
    val lockfiles = SbtUtils.evaluateTask(AdeptKeys.adeptLockfiles, SbtUtils.currentProject(state), state)
    val maybeParsedTermTargetConf =
      if (args.size == 4 && args(2) == "-v") {
        Right((args(1), args(0), Set(Constraint(AttributeDefaults.VersionAttribute, Set(args(3))))))
      } else if (args.size == 3) {
        Right((args(0), defaultConf, Set(Constraint(AttributeDefaults.VersionAttribute, Set(args(2))))))
      } else if (args.size == 2) {
        Right(args(1), args(0), Set.empty[Constraint])
      } else if (args.size == 1) {
        Right(args(0), defaultConf, Set.empty[Constraint])
      } else {
        Left("Wrong number of parameters: <OPTIONAL: conf> <search expression> <OPTIONAL: -v VERSION>.\nExample: 'ah install test akka-actor/ -v 2.2.1'")
      }
    maybeParsedTermTargetConf match {
      case Right((term, targetConf, constraints)) =>
        val searchResults = adepthub.search(term, constraints, allowLocalOnly = true)
        val uniqueModule = AdeptHub.getUniqueModule(term, searchResults).fold( errorMsg => Failure(UserInputException(errorMsg)), res => Success(res))
        
        val result = for {
          (baseIdString, variants) <- uniqueModule
          thisIvyConfig <- AdeptSbtUtils.getTargetConf(ivyConfigurations, targetConf)
        } yield {
          val allIvyTargetConfs = Seq(thisIvyConfig) ++ SbtUtils.getAllExtendingConfig(logger, thisIvyConfig, ivyConfigurations)
          val results = allIvyTargetConfs.map { targetIvyConf => //TODO: extract methods below - this is too much for me to read!
            val lockfileFile = lockfileGetter(targetIvyConf.name)
            val lockfile = Lockfile.read(lockfileFile)

            val (requirements, newRequirements) = AdeptHub.newLockfileRequirements(baseIdString, variants, confs, lockfile)
            val inputContext = AdeptHub.newLockfileContext(AdeptHub.searchResultsToContext(searchResults), lockfile)
            val overrides = inputContext //make sure what we want is what we get
            //get new repositories
            adepthub.downloadLocations(searchResults)

            //get lockfile locations:
            adepthub.downloadLockfileLocations(newRequirements, lockfile)

            val result = adepthub.resolve(
              requirements = requirements,
              inputContext = inputContext,
              overrides = overrides,
              scalaBinaryVersion = scalaBinaryVersion,
              majorJavaVersion= majorJavaVersion,
              minorJavaVersion = minorJavaVersion) match {
                case Right((resolveResult, lockfile)) =>
                  if (!lockfileFile.getParentFile().isDirectory() && !lockfileFile.getParentFile().mkdirs()) throw new Exception("Could not create directory for lockfile: " + lockfileFile.getAbsolutePath)
                  val msg = "installed " + baseIdString + " (" + variants.map(variant => VersionRank.getVersion(variant).map(_.value).getOrElse(variant.toString)).mkString("\n") + ")"
                  Right((lockfile, targetIvyConf.name, lockfileFile, msg))
                case Left(error) =>
                  Left(targetConf -> AdeptHub.renderErrorReport(requirements, inputContext, overrides, error))
              }
            result
          }
          if (results.forall(_.isRight)) {
            val msgs = results.map {
              case Right((lockfile, targetConf, file, msg)) =>
                adepthub.writeLockfile(lockfile, file)
                Success(targetConf -> msg)
              case something => throw new Exception("Expected a right here but got: " + something + " in " + results)
            }
            Success(msgs.map{ case Success((conf, msg)) => conf + ": " + msg}.mkString("\n"))
          } else {
            val msgs = results.collect {
              case Left(msg) => msg
            }
            Failure(UserInputException(msgs.map { case (conf, msg) => "For: " + conf + " got:\n" + msg }.mkString("\n")))
          }
        }//yield ends here...
        
        result.flatten match {
          case Success(msg) =>
            logger.info(msg)
            state
          case Failure(u: UserInputException) =>
            logger.error(u.msg)
            state.fail
          case Failure(e) =>
            throw e
        }
      case Left(errorMsg) =>
        logger.error(errorMsg)
        state.fail
    }
  }
}
