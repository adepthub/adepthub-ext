package adepthub.models

import adept.resolution.models._
import adept.repository.models._
import adept.repository.metadata.VariantMetadata


sealed class SearchResult(val variant: Variant, val repository: RepositoryName, val isImport: Boolean)

case class ImportSearchResult(override val variant: Variant, override val repository: RepositoryName) extends SearchResult(variant, repository, isImport = true)


case class GitSearchResult(override val variant: Variant, override val repository: RepositoryName, commit: Commit, locations: Seq[String], isOffline: Boolean = false) extends SearchResult(variant, repository, isImport = false)

object GitSearchResult {
  import play.api.libs.json.Format
  import play.api.libs.json._
  import play.api.libs.functional.syntax._

  implicit val format: Format[GitSearchResult] = {
    (
      (__ \ "id").format[String] and
      (__ \ "variant").format[VariantMetadata] and
      (__ \ "repository").format[String] and
      (__ \ "commit").format[String] and
      (__ \ "locations").format[Seq[String]])({
        case (id, variant, repository, commit, locations) =>
         GitSearchResult(variant.toVariant(Id(id)), RepositoryName(repository), Commit(commit), locations)
      },
        unlift({ gsr: GitSearchResult =>
          val GitSearchResult(variant, repository, commit, locations, offline) = gsr 
          Some(variant.id.value, VariantMetadata.fromVariant(variant), repository.value, commit.value, locations)
        }))
  } 
}