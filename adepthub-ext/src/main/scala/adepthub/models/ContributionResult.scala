package adepthub.models

import adept.repository.models.RepositoryName
import adept.repository.models.Commit
import play.api.libs.json.Format

case class ContributionResult(repository: RepositoryName, commit: Commit, locations: Seq[String])

object ContributionResult {
  import play.api.libs.functional.syntax._
  import play.api.libs.json._
  implicit val format : Format[ContributionResult] = {
    (
      (__ \ "repository").format[String] and
      (__ \ "commit").format[String] and
      (__ \ "locations").format[Seq[String]])({
        case (repository, commit, locations) =>
          ContributionResult(RepositoryName(repository), Commit(commit), locations)
      }, unlift({ c: ContributionResult=>
        val ContributionResult(repository, commit, locations) = c
        Some((repository.value, commit.value, locations))
      }))
  }

}