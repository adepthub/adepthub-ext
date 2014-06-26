package adepthub.models

import adept.repository.models.RepositoryName
import adept.repository.models.Commit
import com.fasterxml.jackson.core.JsonParser
import adept.services.JsonService
import adept.exceptions.JsonMissingFieldException

case class ContributionResult(repository: RepositoryName, commit: Commit, locations: Seq[String])

object ContributionResult {
  def fromJson(parser: JsonParser): ContributionResult = {
    var repository: Option[RepositoryName] = None
    var commit: Option[Commit] = None
    var locations = Seq.empty[String]

    JsonService.parseObject(parser, (parser, fieldName) => {
      fieldName match {
        case "repository" =>
          repository = Some(RepositoryName(parser.getValueAsString))
        case "commit" => commit = Some(Commit(parser.getValueAsString))
        case "locations" => locations = JsonService.parseStringSeq(parser)
      }
    })

    ContributionResult(repository.getOrElse(throw JsonMissingFieldException("repository")),
      commit.getOrElse(throw JsonMissingFieldException("commit")), locations)
  }
}
