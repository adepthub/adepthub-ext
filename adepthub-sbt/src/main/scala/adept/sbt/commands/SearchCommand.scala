package adept.sbt.commands

import adept.AdeptHub
import adept.resolution.models._
import sbt.State

object SearchCommand {
  import sbt.complete.DefaultParsers._
  def using(adepthub: AdeptHub) = {
    (token("search") ~> (Space ~> NotSpaceClass.+).+).map { args =>
      new SearchCommand(args.map(_.mkString), adepthub)
    }

  }
}

class SearchCommand(args: Seq[String], adepthub: AdeptHub) extends AdeptCommand {
  def realExecute(state: State): State = {
    val logger = state.globalLogging.full

    val term = args.head
    val constraints = Set.empty[Constraint]
    val searchResults = adepthub.search(term, constraints, allowLocalOnly = false)
    val renderedSearchResults = AdeptHub.renderSearchResults(searchResults, term, constraints)

    logger.info(renderedSearchResults)
    state
  }
}
