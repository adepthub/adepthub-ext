package logging

import org.slf4j.ILoggerFactory
import org.slf4j.Logger

class SbtLoggerFactory extends ILoggerFactory {
  override def getLogger(name: String): Logger = {
    SbtLogger.currentSbtLogger.value match {
      case Some(sbtLogger) => new SbtLogger(sbtLogger)
      case None => new NullLogger
    }
  }
}
