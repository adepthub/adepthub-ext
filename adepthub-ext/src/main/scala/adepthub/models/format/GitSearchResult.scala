package adepthub.models.format

import play.api.libs.json._
import play.api.libs.functional.syntax._
import adept.repository.metadata.VariantMetadata
import adept.repository.models.{RankId, Commit, RepositoryName}
import adept.resolution.models.Id

object GitSearchResult {
    implicit val format: Format[adepthub.models.GitSearchResult] = {
      (
        (__ \ "id").format[String] and
        (__ \ "variant").format[VariantMetadata] and
        (__ \ "rank").format[String] and
        (__ \ "repository").format[String] and
        (__ \ "commit").format[String] and
        (__ \ "locations").format[Seq[String]])({
          case (id, variant, rank, repository, commit, locations) =>
           adepthub.models.GitSearchResult(variant.toVariant(Id(id)), RankId(rank), RepositoryName(repository), Commit(commit), locations)
        },
          unlift({ gsr: adepthub.models.GitSearchResult =>
            val adepthub.models.GitSearchResult(variant, rank, repository, commit, locations, offline) = gsr
            Some(variant.id.value, VariantMetadata.fromVariant(variant), rank.value, repository.value, commit.value, locations)
          }))
    }
}
