package adept.sbt.commands

import java.io.File

import adept.AdeptHub
import adept.lockfile.Lockfile
import adept.sbt.{AdeptKeys, SbtUtils, UserInputException}
import org.apache.commons.io.FileUtils
import sbt.State

object ContributeCommand {

  import sbt.complete.DefaultParsers._

  def using(scalaBinaryVersion: String, majorJavaVersion: Int, minorJavaVersion: Int, confs: Set[String],
            ivyConfigurations: Seq[sbt.Configuration], lockfileGetter: String => File, adepthub: AdeptHub) = {
    (token("contribute-imports") ~> (Space ~> NotSpaceClass.+).*).map { args =>
      new ContributeCommand(args.map(_.mkString), scalaBinaryVersion, majorJavaVersion, minorJavaVersion,
        confs, ivyConfigurations, lockfileGetter, adepthub)
    }
  }
}

class ContributeCommand(args: Seq[String], scalaBinaryVersion: String, majorJavaVersion: Int,
                        minorJavaVersion: Int, confs: Set[String], ivyConfigurations: Seq[sbt.Configuration],
                        lockfileGetter: String => File, adepthub: AdeptHub) extends AdeptCommand {
  def realExecute(state: State): State = {
    val logger = state.globalLogging.full
    if (args.isEmpty) {
      logger.error("Wrong number of arguments: ah contribute-imports <package-spec>")
      state.fail
    } else {
      val maybeInstalled = try {
        // TODO: Get imported packages (incl. dependencies) from installIvyPackage
        new IvyInstallCommand(args, scalaBinaryVersion, majorJavaVersion, minorJavaVersion,
          confs, ivyConfigurations, lockfileGetter, adepthub).installIvyPackage(state)
      } catch {
        case u: UserInputException => u
      }

      try {
        maybeInstalled match {
          case _ =>
            // Find the current project's lockfiles
            val lockfiles = SbtUtils.evaluateTask(AdeptKeys.adeptLockfiles, SbtUtils.currentProject(state),
              state)
            // TODO: Supply all imports, incl. dependencies
            val results = adepthub.contribute()
            // Write contributions to lockfiles
            lockfiles.foreach { case (conf, lockfileFile) =>
              val lockfile = Lockfile.read(lockfileFile)
              logger.debug(s"Updating lockfile ${lockfileFile.getAbsolutePath} with contributions")
              adepthub.writeLockfile(adept.Contribute.updateWithContributions(lockfile, results),
                lockfileFile)
            }

            state
          case UserInputException =>
            state.fail
        }
      } finally {
        FileUtils.deleteDirectory(adepthub.importsDir)
      }
    }
  }
}
