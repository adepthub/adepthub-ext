package adept

import adept.resolution.models._
import adept.repository.models._
import adept.repository._
import java.io.File
import adept.ext.JavaVersions
import adept.ext.AttributeDefaults
import adept.resolution.Resolver
import adept.resolution.resolver.models.ResolveResult

private[adept] object Resolve {
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

  def offlineResolve(baseDir: File, adeptHub: AdeptHub)(requirements: Set[Requirement], inputContext: Set[ResolutionResult], overrides: Set[ResolutionResult] = Set.empty, importsDir: Option[File] = None): Either[ResolveErrorReport, ResolveResult] = {
    val overriddenInputContext = GitLoader.applyOverrides(inputContext, overrides)
    val context = GitLoader.computeTransitiveContext(baseDir, overriddenInputContext, importsDir)
    val overriddenContext = GitLoader.applyOverrides(context, overrides)
    val contextRequiringPulls = overriddenContext.flatMap { v =>
      v.commit match {
        case Some(commit) =>
          val gitRepository = new GitRepository(baseDir, v.repository)
          if (gitRepository.exists && gitRepository.hasCommit(commit))
            None
          else {
            Some(v)
          }
        case None =>
          None
      }
    }
    val transitiveLocations = GitLoader.computeTransitiveLocations(baseDir, overriddenInputContext, contextRequiringPulls, importsDir)
    transitiveLocations.foreach { locations =>
      adeptHub.get(locations.name, locations.uris)
    }

    val (major, minor) = JavaVersions.getMajorMinorVersion(this.getClass)
    val providedVariants = Set("", "/config/runtime", "/config/provided", "/config/system", "/config/default", "/config/compile", "/config/master").map { config =>
      val id = Id("org.scala-lang/scala-library" + config)
      Variant(id, attributes = Set(Attribute(AttributeDefaults.BinaryVersionAttribute, Set("2.10"))))
    } ++ JavaVersions.getVariant(major, minor)

    val loader = new GitLoader(baseDir, overriddenContext, cacheManager = adeptHub.cacheManager, unversionedBaseDirs = importsDir.toSet, loadedVariants = providedVariants, progress = adeptHub.progress)
    val resolver = new Resolver(loader)
    val result = resolver.resolve(requirements)
    if (result.isResolved) Right(result)
    else Left(createErrorReport(requirements, inputContext, overrides, overriddenContext, result))
  }
  
  def createErrorReport(requirements: Set[Requirement], resolutionResults: Set[ResolutionResult], overrides: Set[ResolutionResult], transitiveContext: Set[ResolutionResult], result: ResolveResult) = {
    println(result)
    null
  }

}