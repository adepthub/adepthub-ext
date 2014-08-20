package adept.sbt.commands

import java.io.File

import adept.AdeptHub
import adept.ext.JavaVersions
import adept.ivy.scalaspecific.ScalaBinaryVersionConverter
import adept.lockfile.{Lockfile, LockfileConverters}
import adept.sbt.{AdeptKeys, SbtUtils}
import sbt.State

object RmCommand {
  import sbt.complete.DefaultParsers._

  def using(scalaBinaryVersion: String, majorJavaVersion: Int, minorJavaVersion: Int, lockfileGetter:
  String => File, adepthub: AdeptHub) = {
    (token("rm") ~> (Space ~> NotSpaceClass.+).+).map { args =>
      new RmCommand(args.map(_.mkString), scalaBinaryVersion, majorJavaVersion, minorJavaVersion,
        lockfileGetter, adepthub)
    }
  }
}

class RmCommand(args: Seq[String], scalaBinaryVersion: String, majorJavaVersion: Int, minorJavaVersion: Int,
                lockfileGetter: String => File, adepthub: AdeptHub) extends AdeptCommand {
  def realExecute(state: State): State = {
    val logger = state.globalLogging.full
    val lockfiles = SbtUtils.evaluateTask(AdeptKeys.adeptLockfiles, SbtUtils.currentProject(state), state)
    val parsedArgs = if (args.size == 1) {
      Right((args(0), lockfiles.keys.toSet))
    } else if (args.size == 2) {
      Right((args(1), Set(args(0))))
    } else
      Left("Wrong number of arguments: ah rm <OPTIONAL: conf> <id>")

    parsedArgs match {
      case Right((expression, confs)) =>
        val existingLockfileConfs = lockfiles.keys.toSet
        val nonExistigConf = confs.find { conf =>
          !existingLockfileConfs.contains(conf)
        }
        nonExistigConf match {
          case Some(conf) =>
            logger.error(s"Cannot find a lockfile for $conf")
            state.fail
          case None =>
            val results = confs.map { conf =>
              val lockfileFile = lockfileGetter(conf)
              val lockfile = Lockfile.read(lockfileFile)
              val requirements = LockfileConverters.requirements(lockfile)
              val inputContext = LockfileConverters.context(lockfile)
              val overrides = inputContext

              val (removeRequirements, keepRequirements) = requirements.partition { requirement =>
                adepthub.matches(expression, requirement.id)
              }
              if (removeRequirements.isEmpty) {
                logger.info(s"Could not find any requirements matching '$expression' in $conf")
                Left()
              } else {
                val javaVariants = JavaVersions.getVariants(majorJavaVersion, minorJavaVersion)
                val sbtRequirements = Set() +
                  JavaVersions.getRequirement(majorJavaVersion, minorJavaVersion) ++
                  ScalaBinaryVersionConverter.getRequirement(scalaBinaryVersion)

                adepthub.resolve(
                  requirements = keepRequirements ++ sbtRequirements,
                  inputContext = inputContext,
                  overrides = overrides,
                  providedVariants = javaVariants) match {
                    case Right((resolveResult, lockfile)) =>
                      if (!lockfileFile.getParentFile.isDirectory && !lockfileFile.getParentFile.mkdirs())
                        throw new Exception("Could not create directory for lockfile: " +
                          lockfileFile.getAbsolutePath)
                      adepthub.writeLockfile(lockfile, lockfileFile)
                      logger.info(s"In $conf removed:\n" + removeRequirements.map(_.id.value).mkString("\n"))
                      Right()
                    case Left(result) =>
                      logger.error("Got an error while resolving so could not remove:\n" +
                        removeRequirements.map(_.id.value).mkString("\n"))
                      logger.debug(AdeptHub.renderErrorReport(result, requirements, inputContext,
                        overrides).msg)
                      Left()
                  }
              }
            }
            if (results.exists(_.isLeft)) state.fail
            else state
        }
      case Left(errorMsg) =>
        logger.error(errorMsg)
        state.fail
    }
  }
} 
