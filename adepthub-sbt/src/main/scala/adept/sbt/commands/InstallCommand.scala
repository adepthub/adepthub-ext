package adept.sbt.commands

import java.io.File

import adept.AdeptHub
import adept.ext.{AttributeDefaults, JavaVersions, VersionRank}
import adept.ivy.scalaspecific.ScalaBinaryVersionConverter
import adept.lockfile.Lockfile
import adept.models.SearchResult
import adept.resolution.models._
import adept.sbt.{AdeptKeys, AdeptSbtUtils, SbtUtils, UserInputException}
import sbt.{Configuration, Logger, State}

import scala.util.{Failure, Success, Try}

object InstallCommand {
  import sbt.complete.DefaultParsers._

  def using(scalaBinaryVersion: String, majorJavaVersion: Int, minorJavaVersion: Int, confs: Set[String],
            ivyConfigurations: Seq[sbt.Configuration], lockfileGetter: String => File, adepthub: AdeptHub) = {
    (token("install") ~> (Space ~> NotSpaceClass.+).+).map { args =>
      new InstallCommand(args.map(_.mkString), scalaBinaryVersion, majorJavaVersion, minorJavaVersion,
        confs, ivyConfigurations, lockfileGetter, adepthub)
    }
  }
}

class InstallCommand(args: Seq[String], scalaBinaryVersion: String, majorJavaVersion: Int,
                     minorJavaVersion: Int, confs: Set[String], ivyConfigurations: Seq[sbt.Configuration],
                     lockfileGetter: String => File, adepthub: AdeptHub) extends AdeptCommand {
  def realExecute(state: State): State = {
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
        Left("Wrong number of parameters: <OPTIONAL: conf> <search expression> <OPTIONAL: -v VERSION>.\n" +
          "Example: 'ah install test akka-actor/ -v 2.2.1'")
      }
    maybeParsedTermTargetConf match {
      case Right((term, targetConf, constraints)) =>
        val highestSearchResultsOnly = {
          val allSearchResults = adepthub.search(term, constraints, allowLocalOnly = true)
          AdeptHub
            .highestVersionedSearchResults(allSearchResults)
            .toSeq //we lose type-info on toSet :(
            .flatMap {
              case (version, searchResults) =>
                searchResults
            }.toSet
        }
        val uniqueModule = AdeptHub.getUniqueModule(term, highestSearchResultsOnly).fold(
          errorMsg => Failure(UserInputException(errorMsg)), res => Success(res))

        val result = for {
          (baseIdString, variants) <- uniqueModule
          thisIvyConfig <- AdeptSbtUtils.getTargetConf(ivyConfigurations, targetConf)
        } yield {
          writeLockFiles(logger, targetConf, highestSearchResultsOnly, baseIdString, variants, thisIvyConfig)
        }

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

  private def writeLockFiles(logger: Logger, targetConf: String, highestSearchResultsOnly: Set[SearchResult],
                             baseIdString: String, variants: Set[Variant], thisIvyConfig: Configuration):
  Try[String] with Product with Serializable = {
    val allIvyTargetConfs = Seq(thisIvyConfig) ++ SbtUtils.getAllExtendingConfig(logger,
      thisIvyConfig, ivyConfigurations)
    //TODO: extract methods below - this is too much for me to read!
    val results = allIvyTargetConfs.map { targetIvyConf =>
      val lockfileFile = lockfileGetter(targetIvyConf.name)
      val lockfile = Lockfile.read(lockfileFile)
      val newRequirements = AdeptHub.variantsAsConfiguredRequirements(variants, baseIdString, confs)
      val requirements = AdeptHub.newLockfileRequirements(newRequirements, lockfile)
      val inputContext = AdeptHub.newLockfileContext(AdeptHub.searchResultsToContext(
        highestSearchResultsOnly), lockfile)
      //make sure what we want is what we get
      val overrides = inputContext
      //get new repositories
      adepthub.downloadLocations(highestSearchResultsOnly)

      //get lockfile locations:
      adepthub.downloadLockfileLocations(newRequirements, lockfile)

      val javaVariants = JavaVersions.getVariants(majorJavaVersion, minorJavaVersion)
      val sbtRequirements = Set() +
        JavaVersions.getRequirement(majorJavaVersion, minorJavaVersion) ++
        ScalaBinaryVersionConverter.getRequirement(scalaBinaryVersion)

      val result = adepthub.resolve(
        requirements = requirements ++ sbtRequirements,
        inputContext = inputContext,
        overrides = overrides,
        providedVariants = javaVariants) match {
        case Right((resolveResult, lockfile)) =>
          if (!lockfileFile.getParentFile.isDirectory && !lockfileFile.getParentFile.mkdirs())
            throw new Exception("Could not create directory for lockfile: " +
              lockfileFile.getAbsolutePath)
          val msg = "installed " + baseIdString + " (" + variants.map(
            variant => VersionRank.getVersion(variant).map(_.value).getOrElse(variant.toString))
            .mkString("\n") + ")"
          Right((lockfile, targetIvyConf.name, lockfileFile, msg))
        case Left(result) =>
          Left(targetConf -> AdeptHub.renderErrorReport(result, requirements, inputContext,
            overrides))
      }
      result
    }
    if (results.forall(_.isRight)) {
      val msgs = results.map {
        case Right((lockfile, targetConf, file, msg)) =>
          adepthub.writeLockfile(lockfile, file)
          Success(targetConf -> msg)
        case something => throw new Exception("Expected a right here but got: " + something +
          " in " + results)
      }
      Success(msgs.map { case Success((conf, msg)) => conf + ": " + msg}.mkString("\n"))
    } else {
      val msgs = results.collect {
        case Left(msg) => msg
      }
      Failure(UserInputException(msgs.map { case (conf, msg) => "For: " + conf + " got:\n" +
        msg
      }.mkString("\n")))
    }
  }
}
