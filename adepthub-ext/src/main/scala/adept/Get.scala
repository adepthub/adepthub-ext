package adept

import adept.repository.GitRepository
import adept.repository.models._
import adept.logging.Logging
import org.eclipse.jgit.lib.ProgressMonitor
import java.io.File

private[adept] object Get extends Logging { //fix logging!
  def get(baseDir: File, passphrase: Option[String], progress: ProgressMonitor)(name: RepositoryName, locations: Set[String]) = {
    val repository = new GitRepository(baseDir, name)
    if (locations.size > 1) logger.warn("Ignoring locations: " + locations.tail)
    val uri = locations.head
    if (!repository.exists) {
      repository.clone(uri, passphrase, progress)
    } else { //repository exists
      repository.addRemoteUri(GitRepository.DefaultRemote, uri)
      repository.pull(GitRepository.DefaultRemote, GitRepository.DefaultBranchName, passphrase)
    }
  }
}