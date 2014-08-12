package adept.sbt

import adept.logging.JavaLogger
import org.eclipse.jgit.lib.TextProgressMonitor

object AdeptDefaults {

  def javaLogger(logger: sbt.Logger) = new JavaLogger {
    override def debug(message: String) = logger.debug(message)
    override def info(message: String) = logger.info(message)
    override def warn(message: String) = logger.warn(message)
    override def error(message: String) = logger.error(message)
    override def error(message: String, exception: Exception) = {
      logger.error(message + "\nStack trace:\n"+exception.getStackTraceString)
    }
  }
  val progress = new TextProgressMonitor
  val javaProgress = new adept.progress.ProgressMonitor {
    override def beginTask(status: String, max: Int) = progress.beginTask(status, max)
    override def update(i: Int) = progress.update(i)
    override def endTask() = progress.endTask()
  }

}
