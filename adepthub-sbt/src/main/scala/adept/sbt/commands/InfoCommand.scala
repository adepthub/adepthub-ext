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
import adept.lockfile.LockfileConverters
import adept.sbt.AdeptDefaults
import adept.sbt.SbtUtils
import adept.ivy.IvyConstants
import adept.ext.AttributeDefaults
import adept.sbt.AdeptKeys

object InfoCommand {
  import sbt.complete.DefaultParsers._
  import sbt.complete._

  def using(lockfileGetter: String => File, adepthub: AdeptHub) = {
    ((token("info") ~> (Space ~> NotSpaceClass.+).*).map { args =>
      new InfoCommand(args.map(_.mkString), lockfileGetter, adepthub)
    })
  }
}

class InfoCommand(args: Seq[String], lockfileGetter: String => File, adepthub: AdeptHub) extends AdeptCommand {
  def execute(state: State): State = {
    val logger = state.globalLogging.full
    val lockfiles = SbtUtils.evaluateTask(AdeptKeys.adeptLockfiles, SbtUtils.currentProject(state), state)
    val parsedArgs = if (args.size == 0) {
      Right(lockfiles.keys.toSet)
    } else if (args.size == 1) {
      Right(Set(args(0)))
    } else
      Left("Wrong number of arguments: ah info <OPTIONAL: conf>")

    parsedArgs match {
      case Right(confs) =>
        confs.foreach { conf =>
          val lockfileFile = lockfileGetter(conf)
          val lockfile = Lockfile.read(lockfileFile)
          logger.info(conf + ":")
          LockfileConverters.info(lockfile).toSeq.sorted.foreach{ info =>
            logger.info("\t"+info)
          }
        }
        state
      case Left(errorMsg) =>
        logger.error(errorMsg)
        state.fail
    }
  }
} 
