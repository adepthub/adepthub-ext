package adept.sbt

import adept.ResolveErrorReport
import adepthub.models.SearchResult
import adept.ext.models.Module
import adept.ext.VersionRank
import adept.resolution.models.Variant
import scala.util.Success
import scala.util.Failure

case class UserInputException(msg: String) extends Exception

object AdeptUtils {
  def getErrorReport(resolveErrorReport: ResolveErrorReport) = {
    val resolveState = resolveErrorReport.result.state
    if (resolveState.isUnderconstrained) {
      resolveErrorReport.message + "\n" +
        "The graph is under-constrained (there are 2 or more variants matching the ids). This is likely due to ivy imports. This will be fixed soon, but until then: AdeptHub can resolve this, if you contribute/upload your ivy imports." + "\n" +
        "To contribute run: 'ah contribute-imports' and try again."
    } else {
      resolveErrorReport.message
    }
  }

  def getIvyConf(ivyConfigurations: Seq[sbt.Configuration], targetConf: String) = {
    val maybeIvyConfig = ivyConfigurations.filter(_.name == targetConf)
    if (maybeIvyConfig.size == 1) {
      Success(maybeIvyConfig.head)
    } else {
      val errorMsg = "Could not find a matching configuration to name: " + targetConf + ". Alternatives: " + ivyConfigurations.map(_.name).mkString(",")
      Failure(UserInputException(errorMsg))
    }
  }

  def getModule(expression: String, searchResults: Set[SearchResult]) = {
    val modules = Module.getModules(searchResults.map(_.variant))
    if (modules.size == 0) {
      val msg = s"Could not find any variants matching '$expression'."
      Failure(UserInputException(msg))
    } else if (modules.size > 1) {
      val msg = s"Found more than one module matching '$expression'.\n" +
        "Results are:\n" + modules.map {
          case ((base, _), variants) =>
            base + "\n" + variants.map(variant => VersionRank.getVersion(variant).map(_.value).getOrElse(variant.toString)).map("\t" + _).mkString("\n")
        }.mkString("\n")
      Failure(UserInputException(msg))
    } else {
      val ((baseIdString, moduleHash), variants) = modules.head
      Success((baseIdString, variants))
    }
  }
}