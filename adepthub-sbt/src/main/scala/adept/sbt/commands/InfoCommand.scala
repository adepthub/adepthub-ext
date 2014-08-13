package adept.sbt.commands

import java.io.File

import adept.AdeptHub
import adept.lockfile.{Lockfile, LockfileConverters}
import adept.sbt.{AdeptKeys, SbtUtils}
import sbt.State

object InfoCommand {
  import sbt.complete.DefaultParsers._

  def using(lockfileGetter: String => File, adepthub: AdeptHub) = {
    (token("info") ~> (Space ~> NotSpaceClass.+).*).map { args =>
      new InfoCommand(args.map(_.mkString), lockfileGetter, adepthub)
    }
  }
}

class InfoCommand(args: Seq[String], lockfileGetter: String => File, adepthub: AdeptHub)
  extends AdeptCommand {
  def realExecute(state: State): State = {
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
