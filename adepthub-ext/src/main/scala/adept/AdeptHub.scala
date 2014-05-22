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
import org.apache.ivy.Ivy
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
    val revision = "2.2.2"
    //    val ivyInstallResults = adepthub.ivyInstall(org, name, revision, Set("master", "compile"), InternalLockfileWrapper.create(Set.empty, Set.empty, Set.empty), ivy = ivy)
    //    adepthub.contribute(importsDir)
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

    //    adepthub.contribute(importsDir, Set.empty)

  } finally {
    cacheManager.shutdown()
  }

}

class AdeptHub(baseDir: File, importsDir: File, url: String, cacheManager: CacheManager, passphrase: Option[String] = None, onlyOnline: Boolean = false, progress: ProgressMonitor = new TextProgressMonitor) extends Logging { //TODO: make logging configurable
  val adept = new Adept(baseDir, cacheManager, passphrase, progress)
  def defaultIvy = IvyUtils.load(ivyLogger = IvyUtils.warnIvyLogger)

  def get(name: RepositoryName, locations: Set[String]) = {
    val repository = new GitRepository(baseDir, name)
    if (locations.size > 1) logger.warn("Ignoring locations: " + locations.tail)
    val uri = locations.head
    if (!repository.exists) {
      println("cloning from: " + uri + " to :" + repository.dir.getAbsolutePath())
      repository.clone(uri, passphrase, progress)
    } else { //if (repository.exists) {
      println("pulling from: " + uri + " to: " + repository.dir.getAbsolutePath())
      repository.addRemoteUri(GitRepository.DefaultRemote, uri)
      repository.pull(GitRepository.DefaultRemote, GitRepository.DefaultBranchName, passphrase)
    }
  }

  def ivyInstall(org: String, name: String, revision: String, configurations: Set[String], lockfile: Lockfile, ivy: Ivy = defaultIvy, useScalaConvert: Boolean = true, forceImport: Boolean = false) = {
    val id = ScalaBinaryVersionConverter.extractId(IvyUtils.ivyIdAsId(org, name))
    val repositoryName = IvyUtils.ivyIdAsRepositoryName(org)
    val foundMatchingVariants = adept.searchRepository(id.value, name = repositoryName, constraints = Set(Constraint(AttributeDefaults.VersionAttribute, Set(revision))))
    val skipImport = !forceImport && !revision.endsWith("SNAPSHOT") && foundMatchingVariants.nonEmpty

    if (!skipImport) {
      val ivyAdeptConverter = new IvyAdeptConverter(ivy)
      ivyAdeptConverter.ivyImport(org, name, revision, progress) match {
        case Right(ivyResults) =>
          val convertedIvyResults = if (useScalaConvert) {
            ivyResults.map { ivyImportResult =>
              ScalaBinaryVersionConverter.convertResultWithScalaBinaryVersion(ivyImportResult)
            }
          } else ivyResults

          val resolutionResults = IvyImportResultInserter.insertAsResolutionResults(importsDir, baseDir, convertedIvyResults, progress)
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
        case Left(errors) => throw new Exception("Ivy import failed: " + errors)
      }
    }
  }

  def onlineSearch(term: String, constraints: Set[Constraint], executionContext: ExecutionContext): Future[Set[GitSearchResult]] = {
    Future {
      ///TODO: future me, I present my sincere excuses for this code: http client sucks! Rewrite this!
      val postRequest = new HttpPost(url + "/api/search")
      postRequest.addHeader("Content-Type", "application/json")
      val jsonRequest = Json.prettyPrint(Json.toJson(SearchRequest(term, constraints)))
      val entity = new StringEntity(jsonRequest)
      postRequest.setEntity(entity)
      val httpClientBuilder = HttpClientBuilder.create()
      val httpClient = httpClientBuilder.build()
      try {
        val response = httpClient.execute(postRequest)
        try {
          val status = response.getStatusLine()
          val responseString = io.Source.fromInputStream(response.getEntity().getContent()).getLines.mkString("\n")

          if (status.getStatusCode() == 200) {
            val jsonString = responseString
            Json.fromJson[Set[GitSearchResult]](Json.parse(jsonString)).asEither match {
              case Right(results) =>
                results.map(_.copy(isOffline = false))
              case Left(error) =>
                throw new Exception("Could not parse AdeptHub response as search results. Got:\n" + responseString)
            }
          } else {
            throw new Exception("AdeptHub returned with: " + status + ":\n" + responseString)
          }
        } finally {
          response.close()
        }
      } finally {
        httpClient.close()
      }
    }(executionContext)
  }

  def mergeSearchResults(offline: Set[GitSearchResult], online: Set[GitSearchResult]): Set[SearchResult] = {
    val offlineRepoCommit = offline.map { result =>
      result.repository -> result.commit
    }
    offline ++ online.filter { result =>
      !offlineRepoCommit(result.repository -> result.commit)
    }
  }

  val defaultTimeout = {
    import scala.concurrent.duration._
    1.minute
  }

  val defaultExecutionContext = {
    scala.concurrent.ExecutionContext.global //TODO: we should probably have multiple different execution contexts for IO/disk/CPU bound operations
  }

  def searchImportRepository(term: String, name: RepositoryName, constraints: Set[Constraint] = Set.empty): Set[ImportSearchResult] = {
    val repository = new Repository(importsDir, name)
    if (repository.exists) {
      VariantMetadata.listIds(repository).flatMap { id =>
        if (adept.matches(term, id)) {
          val variants = RankingMetadata.listRankIds(id, repository).flatMap { rankId =>
            val ranking = RankingMetadata.read(id, rankId, repository)
              .getOrElse(throw new Exception("Could not read rank id: " + (id, rankId, repository.dir.getAbsolutePath)))
            ranking.variants.map { hash =>
              VariantMetadata.read(id, hash, repository, checkHash = true).map(_.toVariant(id))
                .getOrElse(throw new Exception("Could not read variant: " + (rankId, id, hash, repository.dir.getAbsolutePath)))
            }.find { variant =>
              constraints.nonEmpty ||
                AttributeConstraintFilter.matches(variant.attributes.toSet, constraints)
            }.map(_ -> rankId)
          }

          variants.map {
            case (variant, rankId) =>
              ImportSearchResult(variant, rankId, repository.name)
          }
        } else {
          Set.empty[ImportSearchResult]
        }
      }
    } else {
      Set.empty[ImportSearchResult]
    }
  }

  def searchImport(term: String, constraints: Set[Constraint] = Set.empty): Set[ImportSearchResult] = {
    Repository.listRepositories(importsDir).flatMap { name =>
      searchImportRepository(term, name, constraints)
    }
  }

  def search(term: String, constraints: Set[Constraint] = Set.empty, onlineTimeout: FiniteDuration = defaultTimeout): Set[SearchResult] = {
    val onlineResults = onlineSearch(term, constraints, defaultExecutionContext)
    val offlineResults = adept.search(term, constraints)
    mergeSearchResults(offline = offlineResults, online = Await.result(onlineResults, onlineTimeout))
  }

  def createErrorReport(requirements: Set[Requirement], resolutionResults: Set[ResolutionResult], overrides: Set[ResolutionResult], transitiveContext: Set[ResolutionResult], result: ResolveResult) = {
    println(result)
    null
  }

  def offlineResolve(requirements: Set[Requirement], inputContext: Set[ResolutionResult], overrides: Set[ResolutionResult] = Set.empty, importsDir: Option[File] = None): Either[ResolveErrorReport, ResolveResult] = {
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
      get(locations.name, locations.uris)
    }

    val (major, minor) = JavaVersions.getMajorMinorVersion(this.getClass)
    val providedVariants = Set("", "/config/runtime", "/config/provided", "/config/system", "/config/default", "/config/compile", "/config/master").map { config =>
      val id = Id("org.scala-lang/scala-library" + config)
      Variant(id, attributes = Set(Attribute(AttributeDefaults.BinaryVersionAttribute, Set("2.10"))))
    } ++ JavaVersions.getVariant(major, minor)

    val loader = new GitLoader(baseDir, overriddenContext, cacheManager = cacheManager, unversionedBaseDirs = importsDir.toSet, loadedVariants = providedVariants, progress = progress)
    val resolver = new Resolver(loader)
    val result = resolver.resolve(requirements)
    if (result.isResolved) Right(result)
    else Left(createErrorReport(requirements, inputContext, overrides, overriddenContext, result))
  }

  def walkTree(file: File): Iterable[File] = {
    val children = new Iterable[File] {
      def iterator = if (file.isDirectory) file.listFiles.iterator else Iterator.empty
    }
    Seq(file) ++: children.flatMap(walkTree(_))
  }
  def getZipEntry(file: File, baseDir: File) = {
    new ZipEntry(file.getAbsolutePath().replace(baseDir.getAbsolutePath(), ""))
  }
  def compress() = {
    val zipFile = File.createTempFile("adept-", "-import.zip")
    logger.debug("Compressing to: " + zipFile.getAbsolutePath)
    val output = new FileOutputStream(zipFile)
    val zipOutput = new ZipOutputStream(output)
    val BufferSize = 4096 //random number
    var bytes = new Array[Byte](BufferSize)
    try {
      walkTree(importsDir).filter(_.isFile).foreach { file =>
        val zipEntry = getZipEntry(file, importsDir)
        val is = new FileInputStream(file)
        try {
          var bytesRead = is.read(bytes)
          zipOutput.putNextEntry(zipEntry)
          while (bytesRead != -1) {
            zipOutput.write(bytes, 0, bytesRead)
            bytesRead = is.read(bytes)
          }
        } finally {
          is.close()
        }
      }
      zipFile
    } finally {
      zipOutput.finish()
      output.close()
      zipOutput.close()
    }
  }

  def sendFile(file: File) = {
    ///TODO: future me, I present my sincere excuses for this code: http client sucks!
    val requestBuilder = RequestBuilder.post()
    requestBuilder.setUri(url + "/api/ivy/import")
    val multipartBuilder = MultipartEntityBuilder.create()
    multipartBuilder.setMode(HttpMultipartMode.STRICT)
    multipartBuilder.addBinaryBody("contribution-zipped-file", file)
    val entity = multipartBuilder.build()
    requestBuilder.setEntity(entity)
    val httpClientBuilder = HttpClientBuilder.create()
    val httpClient = httpClientBuilder.build()
    try {
      logger.info("Uploading contribution to AdeptHub - this might take a while...")
      val response = httpClient.execute(requestBuilder.build())
      try {
        val status = response.getStatusLine()
        val responseString = io.Source.fromInputStream(response.getEntity().getContent()).getLines.mkString("\n")

        if (status.getStatusCode() == 200) {
          val jsonString = responseString
          Json.fromJson[Seq[ContributionResult]](Json.parse(jsonString)).asEither match {
            case Left(errors) =>
              logger.debug(errors.mkString(","))
              throw new Exception("Could not parse contribution from AdeptHub! Aborting...")
            case Right(results) =>
              results.foreach { result =>
                val repository = new GitRepository(baseDir, result.repository)
                if (!repository.exists) {
                  if (result.locations.size > 1) logger.warn("Ignoring locations: " + result.locations.tail)
                  val uri = result.locations.head
                  repository.clone(uri, passphrase, progress)
                } else if (repository.exists) {
                  result.locations.foreach { location =>
                    repository.addRemoteUri(GitRepository.DefaultRemote, location)
                  }
                  repository.pull(GitRepository.DefaultRemote, GitRepository.DefaultBranchName, passphrase)
                } else {
                  logger.warn("Ignoring " + result)
                }
              }
          }
        } else {
          throw new Exception("AdeptHub returned with: " + status + ":\n" + responseString)
        }
      } finally {
        response.close()
      }
    } finally {
      httpClient.close()
    }
  }

  def contribute(importsDir: File) = {
    sendFile(compress())
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