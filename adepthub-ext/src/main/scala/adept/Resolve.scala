package adept

import adept.resolution.models._
import adept.repository.models._
import adept.repository._
import java.io.File
import adept.ext.JavaVersions
import adept.ext.AttributeDefaults
import adept.resolution.Resolver
import adept.resolution.resolver.models.ResolveResult
import adept.artifact.models.ArtifactHash
import adept.repository.metadata.ArtifactMetadata
import adept.artifact.models.Artifact
import adept.lockfile.InternalLockfileWrapper

case class ResolveErrorReport(message: String, result: ResolveResult)

private[adept] object Resolve {

  def offlineResolve(adeptHub: AdeptHub)(requirements: Set[Requirement], inputContext: Set[ResolutionResult], overriddenInputContext: Set[ResolutionResult], overriddenContext: Set[ResolutionResult], providedVariants: Set[Variant], overrides: Set[ResolutionResult] = Set.empty, importsDir: Option[File] = None) = {
    val loader = new GitLoader(adeptHub.baseDir, overriddenContext, cacheManager = adeptHub.cacheManager, unversionedBaseDirs = importsDir.toSet, loadedVariants = providedVariants, progress = adeptHub.progress)
    val resolver = new Resolver(loader)
    val result = resolver.resolve(requirements)
    if (result.isResolved) Right(result)
    else Left(createErrorReport(requirements, inputContext, overrides, overriddenContext, result))
  }

  def createErrorReport(requirements: Set[Requirement], resolutionResults: Set[ResolutionResult], overrides: Set[ResolutionResult], transitiveContext: Set[ResolutionResult], result: ResolveResult) = {
    ResolveErrorReport(result.toString, result)
  }

  //
  //  def onlineResolve(requirements: Set[Requirement], variants: Set[VariantInfo]): Future[Either[String, ResolveResult]] = {
  //    ???
  //  }
  //
  //  def canOfflineResolve(repositories: Set[RepositoryInfo]): Boolean = {
  //    repositories.forall { repositoryInfo =>
  //      val repository = new GitRepository(baseDir, repositoryInfo.repository)
  //      repository.exists && repository.hasCommit(repositoryInfo.commit)
  //    }
  //  }
  //
  //  def updateOffline(repositories: Set[RepositoryInfo])(executionContext: ExecutionContext): Future[Either[Set[RepositoryInfo], Set[RepositoryInfo]]] = {
  //    val updates = repositories.flatMap { repositoryInfo =>
  //      val repository = new GitRepository(baseDir, repositoryInfo.repository)
  //      repositoryInfo.locations.uris.map { uri =>
  //        repository.addRemoteUri(GitRepository.DefaultRemote, uri)
  //        Future {
  //          if (repository.hasCommit(repositoryInfo.commit)) {
  //            repositoryInfo
  //          } else {
  //            repositoryInfo.copy(commit = repository.pull(passphrase, progress = progress))
  //          }
  //        }(defaultExecutionContext)
  //      }
  //
  //    }
  //    ???
  //  }
  //
  //  def formatUpdateError(failedRepositories: Set[RepositoryInfo]): Either[String, ResolveResult] = {
  //    ???
  //  }
  //
  //  def resolve(requirements: Set[Requirement], variants: Set[VariantInfo], onlineTimeout: FiniteDuration = defaultTimeout) = {
  //    if (canOfflineResolve(repositories) && !onlyOnline) { //if possible use offline
  //      offlineResolve(requirements, variants)
  //    } else if (onlyOnline) { //or use only online if specified
  //      val onlineResults = onlineResolve(requirements, variants, repositories)
  //      onlineResults.onSuccess {
  //        case Right(results) =>
  //          if (results.isResolved) {
  //            updateOffline(repositories)(defaultExecutionContext)
  //          }
  //      }(defaultExecutionContext)
  //      onlineResults
  //    } else { //if not, try offline update & resolve and hitting online then get the one that finishes first
  //      val onlineResults = onlineResolve(requirements, variants, repositories)
  //      val offlineRepositories = updateOffline(repositories)(defaultExecutionContext)
  //      val offlineResults = offlineRepositories.flatMap {
  //        case Right(_) => offlineResolve(requirements, variants, repositories)
  //        case Left(error) => Future { formatUpdateError(error) }(defaultExecutionContext)
  //      }(defaultExecutionContext)
  //      Future.find(Set(onlineResults, offlineResults)) { result =>
  //        ???
  //      }(defaultExecutionContext)
  //      ???
  //    }
  //  }
}