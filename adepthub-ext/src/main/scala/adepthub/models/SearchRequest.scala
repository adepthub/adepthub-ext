package adepthub.models

import java.io.InputStream

import adept.resolution.models._
import adept.services.JsonService

case class SearchRequest(term: String, constraints: Set[Constraint]) {
  lazy val jsonString = JsonService.writeJson({generator =>
    generator.writeStringField("term", term)
    JsonService.writeArrayField("constraints", constraints, generator)
  })
}

object SearchRequest {
  def readJson(is: InputStream): SearchRequest = {
    JsonService.parseJson(is, Map(
      ("term", _.getValueAsString),
      ("term", JsonService.parseSet[Constraint](_, Constraint.fromJson))
    ),
      valueMap => SearchRequest(valueMap.getString("term"), valueMap.getSet[Constraint]("constraints")))._1
  }
}
