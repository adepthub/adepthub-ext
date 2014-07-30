package adept.sbt.commands

import adept.AdeptHub
import adept.lockfile.Lockfile
import adept.sbt.{AdeptKeys, SbtUtils}
import org.apache.commons.io.FileUtils
import sbt.State

object ContributeCommand {
  import sbt.complete.DefaultParsers._

  def using(adepthub: AdeptHub) = {
    token("contribute-imports").map { _ =>
      new ContributeCommand(adepthub)
    }
  }
}

class ContributeCommand(adepthub: AdeptHub) extends AdeptCommand {
  def execute(state: State): State = {
    val lockfiles = SbtUtils.evaluateTask(AdeptKeys.adeptLockfiles, SbtUtils.currentProject(state), state)
    val results = adepthub.contribute()
    lockfiles.foreach { case (conf, lockfileFile) =>
      val lockfile = Lockfile.read(lockfileFile)
      adepthub.writeLockfile(adept.Contribute.updateWithContributions(lockfile, results), lockfileFile)
    }
    FileUtils.deleteDirectory(adepthub.importsDir)

    state
  }
}
