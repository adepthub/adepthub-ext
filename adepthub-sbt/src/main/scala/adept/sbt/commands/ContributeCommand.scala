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

object ContributeCommand {
  import sbt.complete.DefaultParsers._
  import sbt.complete._

  def using(adepthub: AdeptHub) = {
    ((token("contribute-imports")).map { _ =>
      new ContributeCommand(adepthub)
    })

  }
}

class ContributeCommand(adepthub: AdeptHub) extends AdeptCommand {
  def execute(state: State): State = {
    val logger = state.globalLogging.full
    adepthub.contribute()
    scala.reflect.io.Directory(adepthub.importsDir).deleteRecursively
    state
  }
}
