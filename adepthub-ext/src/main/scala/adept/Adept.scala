package adept

import java.io.File
import net.sf.ehcache.CacheManager
import org.eclipse.jgit.lib.ProgressMonitor
import adept.logging.Logging
import adept.resolution.models.Id
import adept.repository.models.RepositoryName
import adept.resolution.models.Constraint
import adept.repository.GitRepository
import adept.repository.metadata.VariantMetadata
import adept.repository.models.RepositoryLocations
import adept.repository.metadata.RankingMetadata
import org.eclipse.jgit.lib.TextProgressMonitor
import adept.repository.AttributeConstraintFilter
import adept.repository.Repository


class Adept(baseDir: File, cacheManager: CacheManager, passphrase: Option[String] = None, progress: ProgressMonitor = new TextProgressMonitor) extends Logging {
  
  private[adept] def matches(term: String, id: Id) = {
    (id.value + Id.Sep).contains(term)
  }

  def searchRepository(term: String, name: RepositoryName, constraints: Set[Constraint] = Set.empty): Set[SearchResult] = {
    val repository = new GitRepository(baseDir, name)
    if (repository.exists) {
      val commit = repository.getHead
      VariantMetadata.listIds(repository, commit).flatMap { id =>
        if (matches(term, id)) {
          val locations: RepositoryLocations = repository.getRemoteUri(GitRepository.DefaultRemote).map { location =>
            RepositoryLocations(repository.name, Set(location))
          }.getOrElse(RepositoryLocations(repository.name, Set.empty))

          val variants = RankingMetadata.listRankIds(id, repository, commit).flatMap { rankId =>
            val ranking = RankingMetadata.read(id, rankId, repository, commit)
              .getOrElse(throw new Exception("Could not read rank id: " + (id, rankId, repository.dir.getAbsolutePath, commit)))
            ranking.variants.map { hash =>
              VariantMetadata.read(id, hash, repository, commit).map(_.toVariant(id))
                .getOrElse(throw new Exception("Could not read variant: " + (rankId, id, hash, repository.dir.getAbsolutePath, commit)))
            }.find { variant =>
              AttributeConstraintFilter.matches(variant.attributes.toSet, constraints)
            }
          }

          variants.map { variant =>
            SearchResult(variant, repository.name, commit, locations, isOffline = true)
          }
        } else {
          Set.empty[SearchResult]
        }
      }
    } else {
      Set.empty[SearchResult]
    }
  }
  
  def search(term: String, constraints: Set[Constraint] = Set.empty): Set[SearchResult] = {
    Repository.listRepositories(baseDir).flatMap { name =>
      searchRepository(term, name, constraints)
    }
  }
}