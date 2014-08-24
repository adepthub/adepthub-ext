package adepthub.models

import adept.artifact.models.JsonSerializable
import adept.repository.models.RepositoryName
import adept.repository.models.Commit
import com.fasterxml.jackson.core.{JsonGenerator, JsonParser}
import adept.services.JsonService

case class ContributionResult(repository: RepositoryName, commit: Commit, locations: Seq[String])
  extends JsonSerializable {
  override def writeJson(generator: JsonGenerator): Unit = {
    generator.writeStringField("repository", repository.value)
    generator.writeStringField("commit", commit.value)
    JsonService.writeStringArrayField("locations", locations, generator)
  }
}

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
