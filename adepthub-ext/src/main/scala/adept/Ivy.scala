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
  def ivyInstall(adept: Adept, adeptHub: AdeptHub, progress: ProgressMonitor)(org: String, name: String, revision: String, configurations: Set[String], lockfile: Lockfile, ivy: _root_.org.apache.ivy.Ivy = adeptHub.defaultIvy, useScalaConvert: Boolean = true, forceImport: Boolean = false) = {
    val id = ScalaBinaryVersionConverter.extractId(IvyUtils.ivyIdAsId(org, name))
    val repositoryName = IvyUtils.ivyIdAsRepositoryName(org)
    val foundMatchingVariants = adept.searchRepository(id.value, name = repositoryName, constraints = Set(Constraint(AttributeDefaults.VersionAttribute, Set(revision))))
    val skipImport = !forceImport && !revision.endsWith("SNAPSHOT") && foundMatchingVariants.nonEmpty

    if (!skipImport) {
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
        case Left(errors) => throw new Exception("Ivy import failed: " + errors)
      }
    }
  }
}