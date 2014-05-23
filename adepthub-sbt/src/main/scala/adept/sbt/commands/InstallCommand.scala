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

object InstallCommand {
  import sbt.complete.DefaultParsers._
  import sbt.complete._

  def using(confs: Set[String], lockfileGetter: String => File, adepthub: AdeptHub) = {
    ((token("install") ~> (Space ~> NotSpaceClass.+).+).map { args =>
      new InstallCommand(args.map(_.mkString), confs, lockfileGetter, adepthub)
    })

  }
}

class InstallCommand(args: Seq[String], confs: Set[String], lockfileGetter: String => File, adepthub: AdeptHub) extends AdeptCommand {
  def execute(state: State): State = {
    val logger = state.globalLogging.full
    val defaultConf = "compile" //TODO: setting
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
      Left("Wrong number of parameters: <OPTIONAL: conf> <search expression> <OPTIONAL: -v VERSION>. Example: ah install test akka-actor/ -v 2.2.1")
    }
    maybeParsedTermTargetConf match {
      case Right((term, targetConf, constraints)) =>
        val searchResults = adepthub.search(term, constraints)
        val lockfileFile = lockfileGetter(targetConf)
        val modules = Module.getModules(searchResults.map(_.variant))
        if (modules.size == 0) {
          logger.error(s"Could not find any variants that matches: $term. Try to do 'ah ivy-install' instead.")
          state.fail
        } else if (modules.size > 1) {
          logger.error(s"Found more than one module for search on term: $term. Try narrowing your search.")
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
          val newInputContext = adepthub.getContext(searchResults)
          val newContextIds = newInputContext.map(_.id)
          val inputContext = newInputContext ++ (InternalLockfileWrapper.context(lockfile).filter { c =>
            //remove old reqs which are overwritten
            !newContextIds(c.id)
          })
          
          //get new repositories
          adepthub.downloadLocations(searchResults)

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
                  logger.error("To contribute run: 'ah contribute-imports' and try again.")
                } else {
                  logger.error(error.message)
                }
                state.fail
            }
        }
      case Left(errorMsg) =>
        logger.error(errorMsg)
        state.fail
    }
  }
}
