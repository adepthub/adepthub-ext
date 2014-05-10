package adepthub

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

case class SearchResults(variant: Variant, repository: RepositoryName, commit: Commit, locations: RepositoryLocations, isOffline: Boolean)
case class RepositoryInfo(repository: RepositoryName, commit: Commit, locations: RepositoryLocations)

class Adepthub(baseDir: File, url: String, onlyOnline: Boolean = false, progress: ProgressMonitor = new TextProgressMonitor) extends Logging { //TODO: make logging configurable
  def onlineSearch(term: String): Future[SearchResults] = {
    ???
  }

  def offlineSearch(term: String): SearchResults = {
    ???
  }

  def mergeSearchResults(offline: SearchResults, online: Future[SearchResults], onlineTimeout: FiniteDuration): SearchResults = {
    ???
  }

  val defaultTimeout = {
    import scala.concurrent.duration._
    1.minute
  }

  val defaultExecutionContext = {
    scala.concurrent.ExecutionContext.global //TODO: change to something else
  }

  def search(term: String, onlineTimeout: FiniteDuration = defaultTimeout): SearchResults = {
    mergeSearchResults(offline = offlineSearch(term), online = onlineSearch(term), onlineTimeout = onlineTimeout)
  }

  def offlineResolve(requirements: Set[Requirement], variants: Set[Variant], repositories: Set[RepositoryInfo]): Future[ResolveResult] = {
    ???
  }

  def onlineResolve(requirements: Set[Requirement], variants: Set[Variant], repositories: Set[RepositoryInfo]): Future[ResolveResult] = {
    ???
  }

  def canOfflineResolve(repositories: Set[RepositoryInfo]): Boolean = {
    ???
  }

  def updateOffline(repositories: Set[RepositoryInfo]): Either[Set[RepositoryInfo], Set[RepositoryInfo]] = {
    ???
  }

  def formatUpdateError(failedRepositories: Set[RepositoryInfo]): Either[String, _] = {
    ???
  }

  def resolve(requirements: Set[Requirement], variants: Set[Variant], repositories: Set[RepositoryInfo], onlineTimeout: FiniteDuration = defaultTimeout) = {
    if (canOfflineResolve(repositories) && !onlyOnline) { //if possible use offline
      offlineResolve(requirements, variants, repositories)
    } else if (onlyOnline) { //or use only online if specified
      val onlineResults = onlineResolve(requirements, variants, repositories)
      onlineResults.onSuccess {
        case results =>
          if (results.isResolved) {
            updateOffline(repositories)
          }
      }(defaultExecutionContext)
      onlineResults
    } else { //if not, try offline update & resolve, while hitting online and get the fastest one
      val onlineResults = onlineResolve(requirements, variants, repositories)
      val offlineResults = blocking {
        Future {
          updateOffline(repositories) match {
            case Right(_) =>
              offlineResolve(requirements, variants, repositories)
            case Left(error) => formatUpdateError(error)
          }
        }(defaultExecutionContext)
      }
      Future.find(Set(onlineResults, offlineResults)) { result =>
        ???
      }(defaultExecutionContext)
      ???
    }
  }
}