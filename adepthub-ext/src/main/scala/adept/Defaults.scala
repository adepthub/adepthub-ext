package adept

import java.io.File
import adept.ivy.IvyUtils
import org.eclipse.jgit.lib.TextProgressMonitor

object Defaults {
  def baseDir = Option(System.getProperty("user.home")).map(new File(_, ".adept")).getOrElse {
    throw new Exception("Could not read AdeptHubs default home dir: set the user.home property to enable it")
  }
  val url = "http://adepthub.com"

  def ivy = IvyUtils.load(ivyLogger = IvyUtils.warnIvyLogger)

  val progress = new TextProgressMonitor

}