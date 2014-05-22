package adept

import adept.ivy.scalaspecific.ScalaBinaryVersionConverter
import adept.ivy.IvyUtils
import adept.ivy.IvyAdeptConverter
import adept.ivy.IvyImportResultInserter
import adept.lockfile.Lockfile
import org.eclipse.jgit.lib.ProgressMonitor
import adept.resolution.models.Constraint
import adept.ext.AttributeDefaults
import adept.ivy.IvyConstants

private[adept] object Ivy {
  def getExisting(adeptHub: AdeptHub)(org: String, name: String, revision: String, configurations: Set[String]) = {
    val id = ScalaBinaryVersionConverter.extractId(IvyUtils.ivyIdAsId(org, name))
    val repositoryName = IvyUtils.ivyIdAsRepositoryName(org)
    val configuredIds = configurations.map(IvyUtils.withConfiguration(id, _))
    val searchResults = adeptHub.search(id.value, constraints = Set(Constraint(AttributeDefaults.VersionAttribute, Set(revision))))
    val modules = searchResults.groupBy(_.variant.attribute(AttributeDefaults.ModuleHashAttribute))
    val foundMatchingVariants = modules.filter {
      case (_, results) =>
        //Since we are removing the scala binary version (e.g. _2.10) from the Id, we have to check the variants for their scala version if not we can risk skipping an import  (then fail on resolution) 
        val matchingScala = results.filter { result =>
          !result.variant.requirements.exists { requirement =>
            if (ScalaBinaryVersionConverter.scalaLibIds(requirement.id)) {
              requirement.constraint(AttributeDefaults.BinaryVersionAttribute) != Constraint(AttributeDefaults.BinaryVersionAttribute, Set(adeptHub.scalaBinaryVersion))
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

  def ivyImport(adept: Adept, adeptHub: AdeptHub, progress: ProgressMonitor)(org: String, name: String, revision: String, configurations: Set[String], lockfile: Lockfile, ivy: _root_.org.apache.ivy.Ivy = adeptHub.defaultIvy, useScalaConvert: Boolean = true, forceImport: Boolean = false) = {
    val ivyAdeptConverter = new IvyAdeptConverter(ivy)
    ivyAdeptConverter.ivyImport(org, name, revision, progress) match {
      case Right(ivyResults) =>
        val convertedIvyResults = if (useScalaConvert) {
          ivyResults.map { ivyImportResult =>
            ScalaBinaryVersionConverter.convertResultWithScalaBinaryVersion(ivyImportResult)
          }
        } else ivyResults

        val resolutionResults = IvyImportResultInserter.insertAsResolutionResults(adeptHub.importsDir, adeptHub.baseDir, convertedIvyResults, progress)
        val installVariants = {
          ivyResults.filter { ivyResult =>
            val variant = ivyResult.variant
            variant.attribute(IvyConstants.IvyOrgAttribute).values == Set(org) &&
              variant.attribute(IvyConstants.IvyNameAttribute).values == Set(name) &&
              variant.attribute(AttributeDefaults.VersionAttribute).values == Set(revision)
          }.map { ivyResult =>
            ivyResult.variant
          }
        }
        Right(true)
      case Left(errors) => throw new Exception("Ivy import failed: " + errors)
    }
  }
}