package adepthub.models

import adept.resolution.models._
import adept.repository.models._
import adept.repository.metadata.VariantMetadata
import adept.services.JsonService


case class SearchRequest(term: String, constraints: Set[Constraint]) {
  lazy val jsonString = JsonService.writeJson({generator =>
    generator.writeStringField("term", term)
    JsonService.writeArrayField("constraints", constraints, generator)
  })
}
