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
import adept.sbt.AdeptDefaults
import adept.sbt.SbtUtils
import adept.ivy.IvyConstants
import adept.ext.AttributeDefaults
import scala.util.Failure
import scala.util.Success
import adept.sbt.AdeptSbtUtils
import adept.sbt.UserInputException
import scala.util.Try
import adept.sbt.UserInputException
import adept.ext.JavaVersions

object IvyInstallCommand {
  import sbt.complete.DefaultParsers._
  import sbt.complete._

  def using(scalaBinaryVersion: String, majorJavaVersion: Int, minorJavaVersion: Int, confs: Set[String], ivyConfigurations: Seq[sbt.Configuration], lockfileGetter: String => File, adepthub: AdeptHub) = {
    ((token("ivy-install") ~> (Space ~> NotSpaceClass.+).+).map { args =>
      new IvyInstallCommand(args.map(_.mkString), scalaBinaryVersion, majorJavaVersion, minorJavaVersion, confs, ivyConfigurations, lockfileGetter, adepthub)
    })

  }
}

class IvyInstallCommand(args: Seq[String], scalaBinaryVersion: String, majorJavaVersion: Int, minorJavaVersion: Int, confs: Set[String], ivyConfigurations: Seq[sbt.Configuration], lockfileGetter: String => File, adepthub: AdeptHub) extends AdeptCommand {
  def execute(state: State): State = {
    val logger = state.globalLogging.full
    val ivySbt = SbtUtils.evaluateTask(sbt.Keys.ivySbt, SbtUtils.currentProject(state), state)

    val (prunedArgs, isForced) = {
      args.filter(_ != "-f") -> args.contains("-f")
    }

    val IvyRevisionRegex = """^\s*\"(.*?)\"\s*%\s*\"(.*?)\"\s*%\s*"(.*?)"\s*$""".r
    val ConfigIvyRevisionRegex = """^\s*\"(.*?)\"\s*%\s*\"(.*?)\"\s*%\s*"(.*?)"\s*%\s*"(.*?)"\s*$""".r
    val IvyRevisionRegexScalaBinary = """^\s*\"(.*?)\"\s*%%\s*\"(.*?)\"\s*%\s*"(.*?)"\s*$""".r
    val ConfigIvyRevisionRegexScalaBinary = """^\s*\"(.*?)\"\s*%%\s*\"(.*?)\"\s*%\s*"(.*?)"\s*%\s*"(.*?)"\s*$""".r

    val defaultConf = "compile"

    val expression = prunedArgs.mkString("\n")
    val maybeMatch = expression match {
      case IvyRevisionRegex(org, name, revision) => Right((defaultConf, (org, name, revision)))
      case IvyRevisionRegexScalaBinary(org, name, revision) => Right((defaultConf, (org, name + "_" + scalaBinaryVersion, revision)))
      case ConfigIvyRevisionRegex(org, name, revision, conf) => Right((conf, (org, name, revision)))
      case ConfigIvyRevisionRegexScalaBinary(org, name, revision, conf) => Right((conf, (org, name + "_" + scalaBinaryVersion, revision)))
      case _ => Left("""Need something matching: "<org>" % "<name>" % "<revision>" or "<org>" % "<name>" % "<revision> % "<conf>"" or "<org>" %% "<name>" % "<revision>", but got: """ + expression)
    }
    maybeMatch match {
      case Left(msg) =>
        logger.error(msg)
        state.fail
      case Right((targetConf, (org, name, revision))) =>
        ivySbt.withIvy(IvyUtils.errorIvyLogger) { ivy =>
          val maybeIvyAccepted = adepthub.ivyImport(org, name, revision, confs, scalaBinaryVersion, ivy = ivy, forceImport = isForced) match {
            case Right(existing) if existing.nonEmpty && !isForced =>
              val alts = Module.getModules(existing.map { searchResult =>
                searchResult.variant
              })
              val msg = "No point in using ivy-install because this module have already been imported. Results:\n" + alts.map {
                case ((base, _), variants) =>
                  base + " version: " + variants.flatMap(VersionRank.getVersion).map(_.value).mkString(",")
              }.mkString("\n") + "\n" +
                "Try:\n" + alts.map {
                  case ((base, _), variants) =>
                    val versions = variants.flatMap(VersionRank.getVersion).map(_.value)
                    val versionString = if (versions.size == 1) {
                      " -v " + versions.head
                    } else {
                      ""
                    }
                    "ah install " + base + "/" + versionString
                }.mkString("\n")
              Failure(UserInputException(msg))
            case Left(errors) =>
              val msg = "Ivy could not resolve:\n" + errors.mkString("\n")
              Failure(UserInputException(msg))
            case Right(searchResults) => Success(searchResults)
          }

          val result: Try[Try[String]] = for {
            //handle user errors:
            _ <- maybeIvyAccepted
            term = ScalaBinaryVersionConverter.extractId(Id(org + "/" + name)).value + "/"
            importedSearchResults = {
              val constraints = Set(Constraint(AttributeDefaults.VersionAttribute, Set(revision)))
              val allSearchResults = adepthub.search(term, constraints,
                allowLocalOnly = false,
                alwaysIncludeImports = true)
              allSearchResults.filter {
                case searchResult: ImportSearchResult => true
                case _ => false
              }
            }
            (baseIdString, variants) <- AdeptHub.getUniqueModule(term, importedSearchResults).fold(errorMsg => Failure(UserInputException(errorMsg)), res => Success(res))
            thisIvyConfig <- AdeptSbtUtils.getTargetConf(ivyConfigurations, targetConf)
          } yield {
            val lockfileFile = lockfileGetter(targetConf)
            val allIvyTargetConfs = SbtUtils.getAllExtendingConfig(logger, thisIvyConfig, ivyConfigurations)

            val results = allIvyTargetConfs.map { targetIvyConf =>
              val lockfile = Lockfile.read(lockfileFile)
              val newRequirements = AdeptHub.variantsAsConfiguredRequirements(variants, baseIdString, confs)
              val requirements = AdeptHub.newLockfileRequirements(newRequirements, lockfile)
              val inputContext = AdeptHub.newLockfileContext(AdeptHub.searchResultsToContext(importedSearchResults), lockfile)
              val overrides = inputContext

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
                    if (!lockfileFile.getParentFile().isDirectory() && !lockfileFile.getParentFile().mkdirs()) throw new Exception("Could not create directory for lockfile: " + lockfileFile.getAbsolutePath)
                    adepthub.writeLockfile(lockfile, lockfileFile)
                    val msg = s"Installed $org#$name!$revision"
                    Right((lockfile, targetConf, lockfileFile, msg))
                  case Left(error) =>
                    Left(targetConf -> AdeptHub.renderErrorReport(error, requirements, inputContext, overrides))
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
              Success(msgs.map { case Success((conf, msg)) => conf + ": " + msg }.mkString("\n"))
            } else {
              val msgs = results.collect {
                case Left(msg) => msg
              }
              Failure(UserInputException(msgs.map { case (conf, msg) => "For: " + conf + " got:\n" + msg }.mkString("\n")))
            }
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
        }
    }
  }
}
