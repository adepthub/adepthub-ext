package adept

import java.io.File
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import adept.logging.Logging
import scala.concurrent.Future
import adept.resolution.models.Id
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.blocking
import adept.resolution.Resolver
import adept.resolution.models.Attribute
import adept.resolution.models.Constraint
import adept.resolution.models.Variant
import adept.resolution.models.Requirement
import adept.repository.AttributeConstraintFilter
import adept.repository.models.RepositoryName
import adept.repository.models.Commit
import adept.repository.models.VariantHash
import adept.resolution.resolver.models.ResolveResult
import adept.repository.models.RepositoryLocations
import adept.repository.models.ResolutionResult
import scala.concurrent.ExecutionContext
import adept.repository.Repository
import adept.repository.GitRepository
import adept.repository.metadata.VariantMetadata
import adept.repository.metadata.RankingMetadata
import adept.ivy.IvyUtils
import adept.ivy.IvyConstants
import adept.ivy.IvyAdeptConverter
import adept.ivy.IvyImportResultInserter
import adept.ivy.IvyRequirements
import adept.ivy.scalaspecific.ScalaBinaryVersionConverter
import org.apache.ivy.Ivy
import org.eclipse.jgit.lib.{ ProgressMonitor, TextProgressMonitor }
import adept.lockfile.{ InternalLockfileWrapper, Lockfile }
import adept.ext.AttributeDefaults
import adept.repository.GitLoader
import net.sf.ehcache.CacheManager
import adept.ext.JavaVersions

case class SearchResult(variant: Variant, repository: RepositoryName, commit: Commit, locations: RepositoryLocations, isOffline: Boolean)
case class VariantInfo(id: Id, hash: VariantHash, repository: RepositoryName, commit: Commit, locations: RepositoryLocations)

case class ResolveErrorReport(message: String, result: ResolveResult)

package object Implicits {
  implicit class RichVariant(variant: Variant) {
    def hash = VariantMetadata.fromVariant(variant).hash
  }
}

object Main extends App { //TODO: remove
  val baseDir = new File(System.getProperty("user.home") + "/.adept")
  val cacheManager = CacheManager.create()
  val adepthub = new Adepthub(baseDir, "http://localhost:9000", cacheManager)

  //  val resourceFile = new File("/Users/freekh/.ivy2/cache/org.javassist/javassist/ivy-3.18.0-GA.xml.original")
  //  
  //  val javacTargetVersion = 
  //    for {
  //      plugin <- scala.xml.XML.loadFile(resourceFile) \\ "plugins" \ "plugin"
  //      if ((plugin \ "groupId").text == "org.apache.maven.plugins")
  //      if ((plugin \ "artifactId").text == "maven-compiler-plugin")
  //    } yield {
  ////      println((plugin \ "groupId"))
  //      (plugin \ "configuration" \  "target").text 
  //    }
  //  println(javacTargetVersion)

  //    adepthub.ivyInstall("org.javassist", "javassist", "3.18.0-GA", Set("master", "compile"), InternalLockfileWrapper.create(Set.empty, Set.empty, Set.empty)).right.get
  val ivy = adepthub.defaultIvy
  ivy.configure(new File("/Users/freekh/Projects/adepthub-ext/adepthub-ext/src/test/resources/sbt-plugin-ivy-settings.xml"))
  val org = "com.typesafe.play"
  val name = "sbt-plugin"
  val revision = "2.2.1"
  //
  //  val org = "org.scala-sbt"
  //  val name = "precompiled-2_9_3"
  //  val revision = "0.13.0"

  val ivyInstallResults = adepthub.ivyInstall(org, name, revision, Set("master", "compile"), InternalLockfileWrapper.create(Set.empty, Set.empty, Set.empty), ivy = ivy)
  if (ivyInstallResults.isLeft) println(ivyInstallResults)
  
  val repository = new GitRepository(baseDir, RepositoryName(org))
  val searchResults = adepthub.search(org + "/" + name + "/", Set(Constraint(AttributeDefaults.VersionAttribute, Set(revision))))
  val forced = searchResults.map { searchResult =>
    val hash = VariantMetadata.fromVariant(searchResult.variant).hash
    ResolutionResult(searchResult.variant.id, searchResult.repository, searchResult.commit, hash)
  }
  println(adepthub.offlineResolve(
    Set(
      (repository.name, Requirement(Id(org + "/" + name + "/config/compile"), Set.empty, Set.empty), repository.getHead)),
    //      (repository.name, Requirement(Id(org + "/" + name + "/config/master"), Set.empty, Set.empty), repository.getHead)),
    forced = forced))

  cacheManager.shutdown()
}

class Adepthub(baseDir: File, url: String, cacheManager: CacheManager, passphrase: Option[String] = None, onlyOnline: Boolean = false, progress: ProgressMonitor = new TextProgressMonitor) extends Logging { //TODO: make logging configurable
  def defaultIvy = IvyUtils.load(ivyLogger = IvyUtils.warnIvyLogger)

  def ivyInstall(org: String, name: String, revision: String, configurations: Set[String], lockfile: Lockfile, ivy: Ivy = defaultIvy, useScalaConvert: Boolean = true) = {
    val id = ScalaBinaryVersionConverter.extractId(IvyUtils.ivyIdAsId(org, name))
    val repositoryName = IvyUtils.ivyIdAsRepositoryName(org)
    val foundMatchingVariants = offlineSearchRepository(id.value, name = repositoryName, constraints = Set(Constraint(AttributeDefaults.VersionAttribute, Set(revision))))
    val skipImport = !revision.endsWith("SNAPSHOT") && foundMatchingVariants.nonEmpty

    if (!skipImport) {
      val ivyAdeptConverter = new IvyAdeptConverter(ivy)
      ivyAdeptConverter.ivyImport(org, name, revision, progress) match {
        case Right(ivyResults) =>
          val convertedIvyResults = if (useScalaConvert) {
            ivyResults.map { ivyImportResult =>
              ScalaBinaryVersionConverter.convertResultWithScalaBinaryVersion(ivyImportResult)
            }
          } else ivyResults

          val resolutionResults = IvyImportResultInserter.insertAsResolutionResults(baseDir, convertedIvyResults, progress)
          val installVariants = {
            ivyResults.filter { ivyResult =>
              val variant = ivyResult.variant
              variant.attribute(IvyConstants.IvyOrgAttribute).values == Set(org) &&
                variant.attribute(IvyConstants.IvyNameAttribute).values == Set(name) &&
                variant.attribute(AttributeDefaults.VersionAttribute).values == Set(revision)
            }.map { ivyResult =>
              ivyResult.variant
            }
          }

          //offlineResolve(requirements, variants, repositories)
          Right()
        case Left(errors) => Left(errors)
      }
    } else {
      println("Skipping ivy import. Found variants: " + foundMatchingVariants.map(_.variant))
      Right()
    }
  }

  private def matches(term: String, id: Id) = {
    (id.value + Id.Sep).contains(term)
  }

  def onlineSearch(term: String): Future[Set[SearchResult]] = {
    ???
  }

  def offlineSearchRepository(term: String, name: RepositoryName, constraints: Set[Constraint] = Set.empty): Set[SearchResult] = {
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
  def offlineSearch(term: String, constraints: Set[Constraint] = Set.empty): Set[SearchResult] = {
    Repository.listRepositories(baseDir).flatMap { name =>
      offlineSearchRepository(term, name, constraints)
    }
  }

  def mergeSearchResults(offline: Set[SearchResult], online: Future[Set[SearchResult]], onlineTimeout: FiniteDuration): Set[SearchResult] = {
    ???
  }

  val defaultTimeout = {
    import scala.concurrent.duration._
    1.minute
  }

  val defaultExecutionContext = {
    scala.concurrent.ExecutionContext.global //TODO: we should probably have multiple different execution contexts for IO/disk/CPU bound operations
  }

  def search(term: String, constraints: Set[Constraint] = Set.empty, onlineTimeout: FiniteDuration = defaultTimeout): Set[SearchResult] = {
    //    mergeSearchResults(offline = offlineSearch(term), online = onlineSearch(term), onlineTimeout = onlineTimeout)
    logger.warn("online search not... online yet .... ")
    offlineSearch(term, constraints)
  }

  def createErrorReport(initCompoundInfo: Set[(RepositoryName, Requirement, Commit)], resolutionResults: Set[ResolutionResult], result: ResolveResult) = {
    println(result)
    null
  }

  def offlineResolve(compoundInfo: Set[(RepositoryName, Requirement, Commit)], forced: Set[ResolutionResult] = Set.empty): Either[ResolveErrorReport, ResolveResult] = {
    val allCompoundInfo = GitLoader.getResolutionResults(baseDir, compoundInfo, progress, cacheManager)
    val allResolutionResults = allCompoundInfo.map {
      case (resolutionResult, _) =>
        resolutionResult
    }
    val (major, minor) = JavaVersions.getMajorMinorVersion(this.getClass)
    val providedVariants = Set("", "/config/runtime", "/config/provided", "/config/system", "/config/default", "/config/compile", "/config/master").map { config =>
      val id = Id("org.scala-lang/scala-library" + config)
      Variant(id, attributes = Set(Attribute(AttributeDefaults.BinaryVersionAttribute, Set("2.10"))))
    } ++ JavaVersions.getVariant(major, minor)
    val forcedIds = forced.map(_.id).toSet
    val forcedResolutionResults = allResolutionResults.filter { resolutionResult =>
      !forcedIds(resolutionResult.id)
    }

    val loader = new GitLoader(baseDir, forcedResolutionResults ++ forced, progress, cacheManager, loadedVariants = providedVariants)
    val resolver = new Resolver(loader)
    val requirements = compoundInfo.map {
      case (_, requirement, _) =>
        requirement
    }
    val resolveResult = resolver.resolve(requirements)
    if (resolveResult.isResolved) {
      Right(resolveResult)
    } else {
      Left(createErrorReport(initCompoundInfo = compoundInfo, resolutionResults = allResolutionResults, result = resolveResult))
    }
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