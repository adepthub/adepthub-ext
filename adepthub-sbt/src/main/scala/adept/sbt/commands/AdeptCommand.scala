package adept.sbt.commands

import net.sf.ehcache.CacheManager
import org.eclipse.jgit.lib.ProgressMonitor
import sbt.State

abstract class AdeptCommand {
  def execute(state: State): State
}