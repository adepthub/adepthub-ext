package adept.sbt

import scala.util.Success
import scala.util.Failure

object AdeptSbtUtils { 
  def getTargetConf(ivyConfigurations: Seq[sbt.Configuration], targetConf: String) = {
    val maybeIvyConfig = ivyConfigurations.filter(_.name == targetConf)
    if (maybeIvyConfig.size == 1) {
      Success(maybeIvyConfig.head)
    } else {
      val errorMsg = "Could not find a matching configuration to name: " + targetConf + ". Alternatives: " +
        ivyConfigurations.map(_.name).mkString(",")
      Failure(UserInputException(errorMsg))
    }
  }
}
