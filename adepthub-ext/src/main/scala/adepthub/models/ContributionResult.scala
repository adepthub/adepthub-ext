package adepthub.models

import adept.repository.models.RepositoryName
import adept.repository.models.Commit
import com.fasterxml.jackson.core.JsonParser
import adept.services.JsonService

case class ContributionResult(repository: RepositoryName, commit: Commit, locations:
Seq[String])

object ContributionResult {
  def fromJson(parser: JsonParser): ContributionResult = {
    JsonService.parseObject(parser, Map(
      ("repository", _.getValueAsString),
      ("commit", _.getValueAsString),
      ("locations", JsonService.parseStringSeq)
    ), valueMap => ContributionResult(RepositoryName(valueMap.getString("repository")),
      Commit(valueMap.getString("commit")), valueMap.getStringSeq("locations")))
  }
}
