package adepthub.models

import adept.repository.models.RepositoryName
import adept.repository.models.Commit
import com.fasterxml.jackson.core.JsonParser
import adept.services.JsonService

case class ContributionResult(repository: RepositoryName, commit: Commit, locations: Seq[String])

object ContributionResult {
  def fromJson(parser: JsonParser): ContributionResult = {
    var repository: Option[RepositoryName] = null
    var commit: Option[Commit] = null
    var locations = Seq[String]()

    JsonService.parseObject(parser, (parser, fieldName) => {
      fieldName match {
        case "repository" =>
          repository = Some(RepositoryName(parser.getValueAsString))
        case "commit" => commit = Some(Commit(parser.getValueAsString))
        case "locations" => locations = JsonService.parseStringSeq(parser)
      }
    })

    ContributionResult(repository.get, commit.get, locations)
  }
}
//
//object ContributionResult {
//  import play.api.libs.functional.syntax._
//  import play.api.libs.json._
//  implicit val format : Format[ContributionResult] = {
//    (
//      (__ \ "repository").format[String] and
//      (__ \ "commit").format[String] and
//      (__ \ "locations").format[Seq[String]])({
//        case (repository, commit, locations) =>
//          ContributionResult(RepositoryName(repository), Commit(commit), locations)
//      }, unlift({ c: ContributionResult=>
//        val ContributionResult(repository, commit, locations) = c
//        Some((repository.value, commit.value, locations))
//      }))
//  }
//
//}
