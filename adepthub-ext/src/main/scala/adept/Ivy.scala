package adept

import adept.ext.AttributeDefaults
import adept.ivy.{IvyAdeptConverter, IvyImportResultInserter, IvyUtils}
import adept.ivy.scalaspecific.ScalaBinaryVersionConverter
import adept.resolution.models.Constraint

private[adept] object Ivy {
  def getExisting(adeptHub: AdeptHub, scalaBinaryVersion: String)(org: String, name: String, revision: String,
                                                                  configurations: Set[String]) = {
    val id = ScalaBinaryVersionConverter.extractId(IvyUtils.ivyIdAsId(org, name))
    val searchResults = adeptHub.search(id.value, constraints = Set(Constraint(
      AttributeDefaults.VersionAttribute, Set(revision))), allowLocalOnly = false)
    val modules = searchResults.groupBy(_.variant.attribute(AttributeDefaults.ModuleHashAttribute))
    val foundMatchingVariants = modules.filter {
      case (_, results) =>
        // Since we are removing the scala binary version (e.g. _2.10) from the Id, we have to check the
        // variants for their scala version if not we can risk skipping an import (then fail on resolution)
        val matchingScala = results.filter { result =>
          !result.variant.requirements.exists { requirement =>
            if (ScalaBinaryVersionConverter.scalaLibIds(requirement.id)) {
              requirement.constraint(AttributeDefaults.BinaryVersionAttribute) !=
                Constraint(AttributeDefaults.BinaryVersionAttribute, Set(scalaBinaryVersion))
            } else {
              false
            }
          }
        }
        matchingScala.size == results.size
    }.flatMap {
      case (_, results) =>
        results
    }
    foundMatchingVariants
  }

  def ivyImport(adeptHub: AdeptHub)(org: String, name: String, revision: String,
                                    ivy: _root_.org.apache.ivy.Ivy = adeptHub.defaultIvy,
                                    useScalaConvert: Boolean = true, forceImport: Boolean = false) = {
    val ivyAdeptConverter = new IvyAdeptConverter(ivy)
    ivyAdeptConverter.ivyImport(org, name, revision, adeptHub.progress) match {
      case Right(ivyResults) =>
        val convertedIvyResults = if (useScalaConvert) {
          ivyResults.map { ivyImportResult =>
            ScalaBinaryVersionConverter.convertResultWithScalaBinaryVersion(ivyImportResult)
          }
        } else ivyResults

        IvyImportResultInserter.insertAsResolutionResults(adeptHub.importsDir, adeptHub.baseDir,
          convertedIvyResults, adeptHub.progress)
        Right()
      case Left(errors) => Left(errors)
    }
  }
}
