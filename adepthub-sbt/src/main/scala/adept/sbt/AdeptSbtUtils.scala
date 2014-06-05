package adept.sbt

import adept.ext.models.ResolveErrorReport
import adepthub.models.SearchResult
import adept.ext.models.Module
import adept.ext.VersionRank
import adept.resolution.models.Variant
import scala.util.Success
import scala.util.Failure

object AdeptSbtUtils { 
  def getTargetConf(ivyConfigurations: Seq[sbt.Configuration], targetConf: String) = {
    val maybeIvyConfig = ivyConfigurations.filter(_.name == targetConf)
    if (maybeIvyConfig.size == 1) {
      Success(maybeIvyConfig.head)
    } else {
      val errorMsg = "Could not find a matching configuration to name: " + targetConf + ". Alternatives: " + ivyConfigurations.map(_.name).mkString(",")
      Failure(UserInputException(errorMsg))
    }
  }
}