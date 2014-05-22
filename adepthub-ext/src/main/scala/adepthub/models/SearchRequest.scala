package adepthub.models

import adept.resolution.models._
import adept.repository.models._
import adept.repository.metadata.VariantMetadata


case class SearchRequest(term: String, constraints: Set[Constraint])

object SearchRequest {
  import play.api.libs.json.Format
  import play.api.libs.json._
  import play.api.libs.functional.syntax._

  implicit val format: Format[SearchRequest] = {
    (
      (__ \ "term").format[String] and
      (__ \ "constraints").format[Map[String, Seq[String]]])({
        case (term, constraints) =>
         SearchRequest(term, constraints.map{ case (name, values) => Constraint(name, values.toSet)}.toSet)
      },
        unlift({ sr: SearchRequest =>
          val SearchRequest(term, constraints) = sr 
          Some(term, constraints.map(c => c.name -> c.values.toSeq).toMap)
        }))
  } 
}