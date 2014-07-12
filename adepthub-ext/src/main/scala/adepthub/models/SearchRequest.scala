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
