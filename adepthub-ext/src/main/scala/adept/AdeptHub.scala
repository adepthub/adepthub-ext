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
import adept.repository.metadata.ResolutionResultsMetadata
import adept.repository.metadata.RepositoryLocationsMetadata
import adept.ivy.IvyUtils
import adept.ivy.IvyConstants
import adept.ivy.IvyAdeptConverter
import adept.ivy.IvyImportResultInserter
import adept.ivy.IvyRequirements
import adept.ivy.scalaspecific.ScalaBinaryVersionConverter
import org.eclipse.jgit.lib.{ ProgressMonitor, TextProgressMonitor }
import adept.lockfile.{ InternalLockfileWrapper, Lockfile }
import adept.ext.AttributeDefaults
import adept.repository.GitLoader
import net.sf.ehcache.CacheManager
import adept.ext.JavaVersions
import adept.ext.VersionRank
import scala.util.matching.Regex
import java.util.zip.ZipEntry
import java.io.FileOutputStream
import java.util.zip.ZipOutputStream
import java.io.FileInputStream
import java.io.BufferedInputStream
import org.apache.http.impl.client.DefaultHttpClient
import org.apache.http.client.methods.HttpPost
import org.apache.http.entity.mime.MultipartEntityBuilder
import org.apache.http.entity.mime.content.StringBody
import org.apache.http.entity.mime.HttpMultipartMode
import org.apache.http.entity.mime.content.FileBody
import org.apache.http.impl.client.HttpClientBuilder
import org.apache.http.entity.ContentType
import org.apache.http.client.methods.RequestBuilder
import play.api.libs.json.Json
import org.apache.http.StatusLine
import _root_.adepthub.models.ContributionResult
import adepthub.models._
import org.apache.http.entity.StringEntity
import adepthub.models.GitSearchResult
import scala.concurrent.Await

case class VariantInfo(id: Id, hash: VariantHash, repository: RepositoryName, commit: Commit, locations: RepositoryLocations)

case class ResolveErrorReport(message: String, result: ResolveResult)

package object Implicits {
  implicit class RichVariant(variant: Variant) {
    def hash = VariantMetadata.fromVariant(variant).hash
  }
}

object Main extends App with Logging { //TODO: remove
  val baseDir = new File(System.getProperty("user.home") + "/.adept")
  val importsDir = new File("imports")

  val cacheManager = CacheManager.create()
  try {
    val adepthub = new AdeptHub(baseDir, importsDir, "http://localhost:9000", cacheManager)
    //    adepthub.ivyInstall("org.javassist", "javassist", "3.18.0-GA", Set("master", "compile"), InternalLockfileWrapper.create(Set.empty, Set.empty, Set.empty)).right.get
    val ivy = adepthub.defaultIvy
    //      ivy.configure(new File("/Users/freekh/Projects/adepthub-ext/adepthub-ext/src/test/resources/sbt-plugin-ivy-settings.xml"))
    //    val org = "com.typesafe.play"
    //    val name = "sbt-plugin"
    //    val revision = "2.2.2"
    val org = "com.typesafe.akka"
    val name = "akka-remote_2.10"
    val revision = "2.3.0"
    val ivyInstallResults = adepthub.ivyInstall(org, name, revision, Set("master", "compile"), InternalLockfileWrapper.create(Set.empty, Set.empty, Set.empty), ivy = ivy)
    adepthub.contribute()
    val searchResults = adepthub.search(ScalaBinaryVersionConverter.extractId(Id(org + "/" + name)).value + "/", Set(Constraint(AttributeDefaults.VersionAttribute, Set(revision))))
    println(searchResults.mkString("\n"))
    val inputContext = searchResults.map {
      case searchResult: ImportSearchResult =>
        val hash = VariantMetadata.fromVariant(searchResult.variant).hash
        ResolutionResult(searchResult.variant.id, searchResult.repository, None, hash)
      case searchResult: GitSearchResult =>
        val hash = VariantMetadata.fromVariant(searchResult.variant).hash
        ResolutionResult(searchResult.variant.id, searchResult.repository, Some(searchResult.commit), hash)
      case searchResult: SearchResult =>
        throw new Exception("Found a search result but expected either an import or a git search result: " + searchResult)
    }
    val passphrase = None
    val progress = new TextProgressMonitor
    searchResults.foreach {
      case result: GitSearchResult if !result.isOffline =>
        adepthub.get(result.repository, result.locations.toSet)
      case _ => //pass
    }
    println(adepthub.offlineResolve(
      Set(
        Requirement(ScalaBinaryVersionConverter.extractId(Id(org + "/" + name + "/config/compile")), Set.empty, Set.empty),
        Requirement(ScalaBinaryVersionConverter.extractId(Id(org + "/" + name + "/config/master")), Set.empty, Set.empty)),
      importsDir = Some(importsDir),
      inputContext = inputContext,
      overrides = inputContext))
  } finally {
    cacheManager.shutdown()
  }

}

class AdeptHub(val baseDir: File, val importsDir: File, val url: String, val cacheManager: CacheManager, val passphrase: Option[String] = None, val onlyOnline: Boolean = false, val progress: ProgressMonitor = new TextProgressMonitor) extends Logging { //TODO: make logging configurable
  val adept = new Adept(baseDir, cacheManager, passphrase, progress)
  def defaultIvy = IvyUtils.load(ivyLogger = IvyUtils.warnIvyLogger)

  def get(name: RepositoryName, locations: Set[String]) = {
    Get.get(baseDir, passphrase, progress)(name, locations)
  }

  def ivyInstall(org: String, name: String, revision: String, configurations: Set[String], lockfile: Lockfile, ivy: _root_.org.apache.ivy.Ivy = defaultIvy, useScalaConvert: Boolean = true, forceImport: Boolean = false) = {
    Ivy.ivyInstall(adept, this, progress)(org, name, revision, configurations, lockfile, ivy, useScalaConvert, forceImport)
  }

  val defaultTimeout = {
    import scala.concurrent.duration._
    1.minute
  }

  val defaultExecutionContext = {
    scala.concurrent.ExecutionContext.global //TODO: we should probably have multiple different execution contexts for IO/disk/CPU bound operations
  }

  def search(term: String, constraints: Set[Constraint] = Set.empty, onlineTimeout: FiniteDuration = defaultTimeout): Set[SearchResult] = {
    val onlineResults = Search.onlineSearch(url)(term, constraints, defaultExecutionContext)
    val offlineResults = adept.search(term, constraints)
    val importResults = Search.searchImport(importsDir, adept)(term, constraints)
    Search.mergeSearchResults(imports = importResults, offline = offlineResults, online = Await.result(onlineResults, onlineTimeout))
  }

  def offlineResolve(requirements: Set[Requirement], inputContext: Set[ResolutionResult], overrides: Set[ResolutionResult] = Set.empty, importsDir: Option[File] = None): Either[ResolveErrorReport, ResolveResult] = {
    Resolve.offlineResolve(baseDir, this)(requirements, inputContext, overrides, importsDir)
  }

  def contribute() = {
    Contribute.contribute(url, baseDir, passphrase, progress, importsDir)
  }

}