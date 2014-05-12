package adept

import java.io.File
import adept.progress.ProgressMonitor
import org.slf4j.Logger
import adept.progress.TextProgressMonitor
import org.slf4j.LoggerFactory
import adept.logging.Logging
import scala.concurrent.Future
import adept.resolution.models.Id
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.blocking
import adept.resolution.models.Variant
import adept.resolution.models.Requirement
import adept.repository.models.RepositoryName
import adept.repository.models.Commit
import adept.resolution.resolver.models.ResolveResult
import adept.repository.models.RepositoryLocations
import scala.concurrent.ExecutionContext
import adept.repository.Repository
import adept.repository.GitRepository
import adept.repository.metadata.VariantMetadata
import adept.ivy.IvyUtils
import adept.ivy.IvyConstants
import org.apache.ivy.Ivy

case class SearchResult(variant: Variant, repository: RepositoryName, commit: Commit, locations: RepositoryLocations, isOffline: Boolean)
case class RepositoryInfo(repository: RepositoryName, commit: Commit, locations: RepositoryLocations)

private[adept] trait AdeptIvy {
  def defaultIvy = IvyUtils.load(ivyLogger = IvyUtils.warnIvyLogger)

  def ivyInstall(ivy: Ivy, org: String, name: String, revision: String, useScalaConvert: Boolean = true) = {
    
  }
}

class Adepthub(baseDir: File, url: String, passphrase: Option[String] = None, onlyOnline: Boolean = false, progress: ProgressMonitor = new TextProgressMonitor) extends Logging with AdeptIvy { //TODO: make logging configurable
  private def matches(term: String, id: Id) = {
    id.value.contains(term)
  }

  def onlineSearch(term: String): Future[Set[SearchResult]] = {
    ???
  }

  def offlineSearch(term: String): Set[SearchResult] = {
    Repository.listRepositories(baseDir).flatMap { name =>
      val repository = new GitRepository(baseDir, name)
      val commit = repository.getHead
      VariantMetadata.listIds(repository, commit).flatMap { id =>
        if (matches(term, id)) {
          val locations: RepositoryLocations = repository.getRemoteUri(GitRepository.DefaultRemote).map { location =>
            RepositoryLocations(repository.name, Set(location))
          }.getOrElse(RepositoryLocations(repository.name, Set.empty))

          val variants = VariantMetadata.listVariants(id, repository, commit).flatMap { hash =>
            VariantMetadata.read(id, hash, repository, commit).map(_.toVariant(id))
          }
          variants.map { variant =>
            SearchResult(variant, repository.name, commit, locations, isOffline = true)
          }
        } else {
          Set.empty[SearchResult]
        }
      }
    }
  }

  def mergeSearchResults(offline: Set[SearchResult], online: Future[Set[SearchResult]], onlineTimeout: FiniteDuration): Set[SearchResult] = {
    logger.warn("online search not... online yet .... ")
    offline
  }

  val defaultTimeout = {
    import scala.concurrent.duration._
    1.minute
  }

  val defaultExecutionContext = {
    scala.concurrent.ExecutionContext.global //TODO: we should probably have multiple different execution contexts for IO/disk/CPU bound operations
  }

  def search(term: String, onlineTimeout: FiniteDuration = defaultTimeout): Set[SearchResult] = {
    mergeSearchResults(offline = offlineSearch(term), online = onlineSearch(term), onlineTimeout = onlineTimeout)
  }

  def offlineResolve(requirements: Set[Requirement], variants: Set[Variant], repositories: Set[RepositoryInfo]): Future[Either[String, ResolveResult]] = {
    
    ???
  }

  def onlineResolve(requirements: Set[Requirement], variants: Set[Variant], repositories: Set[RepositoryInfo]): Future[Either[String, ResolveResult]] = {
    ???
  }

  def canOfflineResolve(repositories: Set[RepositoryInfo]): Boolean = {
    repositories.forall { repositoryInfo =>
      val repository = new GitRepository(baseDir, repositoryInfo.repository)
      repository.exists && repository.hasCommit(repositoryInfo.commit)
    }
  }

  def updateOffline(repositories: Set[RepositoryInfo])(executionContext: ExecutionContext): Future[Either[Set[RepositoryInfo], Set[RepositoryInfo]]] = {
    val updates = repositories.flatMap { repositoryInfo =>
      val repository = new GitRepository(baseDir, repositoryInfo.repository)
      repositoryInfo.locations.uris.map { uri =>
        repository.addRemoteUri(GitRepository.DefaultRemote, uri)
        Future {
          if (repository.hasCommit(repositoryInfo.commit)) {
            repositoryInfo
          } else {
            repositoryInfo.copy(commit = repository.pull(passphrase, progress = ???))
          }
        }(defaultExecutionContext)
      }

    }
    ???
  }

  def formatUpdateError(failedRepositories: Set[RepositoryInfo]): Either[String, ResolveResult] = {
    ???
  }

  def resolve(requirements: Set[Requirement], variants: Set[Variant], repositories: Set[RepositoryInfo], onlineTimeout: FiniteDuration = defaultTimeout) = {
    if (canOfflineResolve(repositories) && !onlyOnline) { //if possible use offline
      offlineResolve(requirements, variants, repositories)
    } else if (onlyOnline) { //or use only online if specified
      val onlineResults = onlineResolve(requirements, variants, repositories)
      onlineResults.onSuccess {
        case Right(results) =>
          if (results.isResolved) {
            updateOffline(repositories)(defaultExecutionContext)
          }
      }(defaultExecutionContext)
      onlineResults
    } else { //if not, try offline update & resolve and hitting online then get the one that finishes first
      val onlineResults = onlineResolve(requirements, variants, repositories)
      val offlineRepositories = updateOffline(repositories)(defaultExecutionContext)
      val offlineResults = offlineRepositories.flatMap {
        case Right(_) => offlineResolve(requirements, variants, repositories)
        case Left(error) => Future { formatUpdateError(error) }(defaultExecutionContext)
      }(defaultExecutionContext)
      Future.find(Set(onlineResults, offlineResults)) { result =>
        ???
      }(defaultExecutionContext)
      ???
    }
  }

}