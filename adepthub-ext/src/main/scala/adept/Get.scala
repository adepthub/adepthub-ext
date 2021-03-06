package adept

import java.io.File

import adept.logging.Logging
import adept.repository.GitRepository
import adept.repository.models._

private[adept] object Get extends Logging { //fix logging!
  def get(baseDir: File, passphrase: Option[String])(name: RepositoryName, locations: Set[String]) = {
    val repository = new GitRepository(baseDir, name)
    if (locations.size > 1) logger.warn("Ignoring locations: " + locations.tail)
    val uri = locations.head
    if (!repository.exists) {
      repository.clone(uri, passphrase)
    } else { //repository exists
      repository.addRemoteUri(GitRepository.DefaultRemote, uri)
      repository.pull(GitRepository.DefaultRemote, GitRepository.DefaultBranchName, passphrase)
    }
  }
}
