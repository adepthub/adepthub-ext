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
import adept.sbt.SbtUtils
import adept.sbt.AdeptKeys
import adept.sbt.AdeptSbtUtils
import scala.util.Success
import scala.util.Failure
import adept.sbt.UserInputException

object SearchCommand {
  import sbt.complete.DefaultParsers._
  import sbt.complete._
  def using(adepthub: AdeptHub) = {
    ((token("search") ~> (Space ~> NotSpaceClass.+).+).map { args =>
      new SearchCommand(args.map(_.mkString), adepthub)
    })

  }
}

class SearchCommand(args: Seq[String], adepthub: AdeptHub) extends AdeptCommand {
  def execute(state: State): State = {
    val logger = state.globalLogging.full

    val term = args.head
    val constraints = Set.empty[Constraint]
    val searchResults = adepthub.search(term, constraints, allowLocalOnly = false)
    val renderedSearchResults = AdeptHub.renderSearchResults(searchResults, term, constraints)

    logger.info(renderedSearchResults)
    state
  }
}
