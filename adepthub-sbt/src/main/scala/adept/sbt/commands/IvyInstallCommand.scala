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
import adept.sbt.SbtUtils
import adept.ivy.IvyConstants
import adept.ext.AttributeDefaults

object IvyInstallCommand {
  import sbt.complete.DefaultParsers._
  import sbt.complete._

  def using(confs: Set[String], lockfileGetter: String => File, adepthub: AdeptHub) = {
    ((token("ivy-install") ~> (Space ~> NotSpaceClass.+).+).map { args =>
      new IvyInstallCommand(args.map(_.mkString), confs, lockfileGetter, adepthub)
    })

  }
}

class IvyInstallCommand(args: Seq[String], confs: Set[String], lockfileGetter: String => File, adepthub: AdeptHub) extends AdeptCommand {
  def execute(state: State): State = {
    val logger = state.globalLogging.full
    val ivySbt = SbtUtils.evaluateTask(sbt.Keys.ivySbt, SbtUtils.currentProject(state), state)
    val scalaBinaryVersion = adepthub.scalaBinaryVersion

    val IvyRevisionRegex = """^\s*\"(.*?)\"\s*%\s*\"(.*?)\"\s*%\s*"(.*?)"\s*$""".r
    val ConfigIvyRevisionRegex = """^(.*?)\s*\"(.*?)\"\s*%\s*\"(.*?)\"\s*%\s*"(.*?)"\s*$""".r
    val IvyRevisionRegexScalaBinary = """^\s*\"(.*?)\"\s*%%\s*\"(.*?)\"\s*%\s*"(.*?)"\s*$""".r
    val ConfigIvyRevisionRegexScalaBinary = """(.*?)^\s*\"(.*?)\"\s*%%\s*\"(.*?)\"\s*%\s*"(.*?)"\s*$""".r

    val defaultConf = "compile"

    val expression = args.mkString("\n")
    val (targetConf, (org, name, revision)) = expression match {
      case IvyRevisionRegex(org, name, revision) => (defaultConf, (org, name, revision))
      case IvyRevisionRegexScalaBinary(org, name, revision) => (defaultConf, (org, name + "_" + scalaBinaryVersion, revision))
      case ConfigIvyRevisionRegex(conf, org, name, revision) => (conf, (org, name, revision))
      case IvyRevisionRegexScalaBinary(conf, org, name, revision) => (conf, (org, name + "_" + scalaBinaryVersion, revision))
      case _ => throw new Exception("""Need something matching: "<org>" % "<name>" % "<revision>" or "<conf> <org>" % "<name>" % "<revision>" or "<org>" %% "<name>" % "<revision>", but got: """ + expression)
    }
    ivySbt.withIvy(IvyUtils.errorIvyLogger) { ivy =>
      adepthub.ivyImport(org, name, revision, confs, ivy = ivy) match {
        case Right(existing) if existing.nonEmpty =>
          val alts = Module.getModules(existing.map { searchResult =>
            searchResult.variant
          })
          logger.error("No point in using ivy-install because this module have already been imported. Results:\n" + alts.map {
            case ((base, _), variants) =>
              base + " version: " + variants.flatMap(VersionRank.getVersion).map(_.value).mkString(",")
          }.mkString("\n"))
          logger.error("Try:\n" + alts.map {
            case ((base, _), variants) =>
              val versions = variants.flatMap(VersionRank.getVersion).map(_.value)
              val versionString = if (versions.size == 1) {
                " -v " + versions.head
              } else {
                ""
              }
              "ah install " + base + "/" + versionString
          }.mkString("\n"))
          state.fail
        case Left(errors) =>
          logger.error("Ivy could not resolve:\n" + errors.mkString("\n"))
          state.fail
        case Right(_) =>
          val constraints = Set(Constraint(AttributeDefaults.VersionAttribute, Set(revision)))
          val allSearchResults = adepthub.search(ScalaBinaryVersionConverter.extractId(Id(org + "/" + name)).value + "/", constraints)
          val importedSearchResults = allSearchResults.filter {
            case searchResult: ImportSearchResult => true
            case _ => false
          }
          val lockfileFile = lockfileGetter(targetConf)
          val modules = Module.getModules(importedSearchResults.map(_.variant))

          if (modules.size == 0) {
            logger.error(s"Could not find any imported variants! Something wrong has happend during import!")
            state.fail
          } else if (modules.size > 1) {
            logger.error(s"Found more than one module after import. This is currently not supported - but will be soon!")
            logger.error("Results are:\n" + modules.map {
              case ((_, base), variants) =>
                base + "\n" + variants.map(variant => VersionRank.getVersion(variant).map(_.value).getOrElse(variant.toString)).map("\t" + _).mkString("\n")
            }.mkString("\n"))
            state.fail
          } else {
            val ((baseIdString, moduleHash), variants) = modules.head
            val configuredIds = confs.map(IvyUtils.withConfiguration(Id(baseIdString), _))

            //Lockfile:
            val lockfile = {
              if (lockfileFile.exists())
                Lockfile.read(lockfileFile)
              else
                InternalLockfileWrapper.create(Set.empty, Set.empty, Set.empty)
            }

            //Requirements:
            val newRequirements = variants.filter { variant =>
              configuredIds(variant.id)
            }.map { variant =>
              Requirement(variant.id, Set.empty[Constraint], Set.empty) //empty constraints because we use variant hash to chose
            }

            val newReqIds = newRequirements.map(_.id)
            val requirements = newRequirements ++ (InternalLockfileWrapper.requirements(lockfile).filter { req =>
              //remove old reqs which are overwritten
              !newReqIds(req.id)
            })

            //Context:
            val newInputContext = adepthub.getContext(importedSearchResults)
            val newContextIds = newInputContext.map(_.id)
            val inputContext = newInputContext ++ (InternalLockfileWrapper.context(lockfile).filter { c =>
              //remove old context values which are overwritten
              !newContextIds(c.id)
            })

            //get lockfile locations:
            adepthub.downloadLockfileLocations(newRequirements, lockfile)

            adepthub.offlineResolve(
              requirements = requirements,
              inputContext = inputContext,
              overrides = inputContext) match {
                case Right((resolveResult, lockfile)) =>
                  if (!lockfileFile.getParentFile().isDirectory() && !lockfileFile.getParentFile().mkdirs()) throw new Exception("Could not create directory for lockfile: " + lockfileFile.getAbsolutePath)
                  adepthub.writeLockfile(lockfile, lockfileFile)
                  state
                case Left(error) =>
                  val resolveState = error.result.state
                  if (resolveState.isUnderconstrained) {
                    logger.error(error.message)
                    logger.error("The graph is under-constrained (there are 2 or more variants matching the ids). This is likely due to ivy imports. This will be fixed soon, but until then: AdeptHub can resolve this, if you contribute/upload your ivy imports.")
                    logger.error("To contribute run: 'ah contribute-imports' then 'ah install " + ScalaBinaryVersionConverter.extractId(Id(org + "/" + name)).value + "/ -v " + revision + "'.")
                  } else {
                    logger.error(error.message)
                  }
                  state.fail
              }
          }
      }
    }
  }
}
