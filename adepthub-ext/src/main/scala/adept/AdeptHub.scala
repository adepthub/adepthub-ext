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
import adept.repository.metadata.ArtifactMetadata
import adept.logging.JavaLogger
import adept.ext.models.Module
import adept.ivy.IvyImportError
import java.io.IOException

object Main extends App with Logging { //TODO: remove
  val baseDir = new File(System.getProperty("user.home") + "/.adept")
  val importsDir = new File("imports")
  val downloadTimeoutMinutes = 60
  val scalaBinaryVersion = "2.10"
  val cacheManager = CacheManager.create()
  val maxArtifactDownloadRetries = 5

  try {
    val (majorJavaVersion, minorJavaVersion) = JavaVersions.getMajorMinorVersion(this.getClass)
    val adepthub = new AdeptHub(baseDir, importsDir, "http://adepthub.com", scalaBinaryVersion, majorJavaVersion, minorJavaVersion, cacheManager)

    val javaLogger = new JavaLogger {
      override def debug(message: String) = logger.debug(message)
      override def info(message: String) = logger.info(message)
      override def warn(message: String) = logger.warn(message)
      override def error(message: String) = logger.error(message)
      override def error(message: String, exception: Exception) = logger.error(message, exception)
    }
    val progress = new TextProgressMonitor
    val javaProgress = new adept.progress.ProgressMonitor {
      override def beginTask(status: String, max: Int) = progress.beginTask(status, max)
      override def update(i: Int) = progress.update(i)
      override def endTask() = progress.endTask()
    }

    //    adepthub.ivyInstall("org.javassist", "javassist", "3.18.0-GA", Set("master", "compile"), InternalLockfileWrapper.create(Set.empty, Set.empty, Set.empty)).right.get
    val ivy = adepthub.defaultIvy
    //          ivy.configure(new File("/Users/freekh/Projects/adepthub-ext/adepthub-ext/src/test/resources/sbt-plugin-ivy-settings.xml"))
    ivy.configure(new File("/Users/freekh/Projects/adepthub-ext/adepthub-ext/src/test/resources/typesafe-ivy-settings.xml"))
    //    val org = "com.typesafe.play"
    //    val name = "sbt-plugin"
    //    val revision = "2.2.2"
    val org = "com.typesafe.akka"
    val name = "akka-actor_" + scalaBinaryVersion
    val revision = "2.2.1"
    adepthub.ivyImport(org, name, revision, Set("master", "compile"), ivy = ivy) match {
      case Right(existing) if existing.isEmpty => adepthub.contribute()
      case Right(existing) if existing.nonEmpty => //skip
      case Left(error) => throw new Exception(error.toString)
    }
    val searchResults = adepthub.search(ScalaBinaryVersionConverter.extractId(Id(org + "/" + name)).value + "/", Set(Constraint(AttributeDefaults.VersionAttribute, Set(revision))), allowOffline = false)
    val newInputContext = adepthub.getContext(searchResults)
    val newReqs = Set(
      Requirement(ScalaBinaryVersionConverter.extractId(Id(org + "/" + name + "/config/compile")), Set.empty, Set.empty),
      Requirement(ScalaBinaryVersionConverter.extractId(Id(org + "/" + name + "/config/master")), Set.empty, Set.empty))

    val lockfileFile = new File("test.adept")
    val lockfile = {
      if (lockfileFile.exists())
        Lockfile.read(lockfileFile)
      else
        InternalLockfileWrapper.create(Set.empty, Set.empty, Set.empty)
    }

    val newReqIds = newReqs.map(_.id)
    val requirements = newReqs ++ (InternalLockfileWrapper.requirements(lockfile).filter { req =>
      //remove old reqs which are overwritten
      !newReqIds(req.id)
    })
    val newContextIds = newInputContext.map(_.id)
    val inputContext = newInputContext ++ (InternalLockfileWrapper.context(lockfile).filter { c =>
      //remove old reqs which are overwritten
      !newContextIds(c.id)
    })

    //get lockfile locations:

    adepthub.offlineResolve(
      requirements = requirements,
      inputContext = inputContext,
      overrides = inputContext) match {
        case Right((resolveResult, lockfile)) =>
          adepthub.writeLockfile(lockfile, lockfileFile)
          lockfile.download(baseDir, downloadTimeoutMinutes, java.util.concurrent.TimeUnit.MINUTES, maxArtifactDownloadRetries, javaLogger, javaProgress)
        case Left(error) =>
          println(error)
      }
  } finally {
    cacheManager.shutdown()
  }

}

class AdeptHub(val baseDir: File, val importsDir: File, val url: String, val scalaBinaryVersion: String, majorJavaVersion: Int, minorJavaVersion: Int, val cacheManager: CacheManager, val passphrase: Option[String] = None, val onlyOnline: Boolean = false, val progress: ProgressMonitor = new TextProgressMonitor) extends Logging { //TODO: make logging configurable
  val adept = new Adept(baseDir, cacheManager, passphrase, progress)
  def defaultIvy = IvyUtils.load(ivyLogger = IvyUtils.warnIvyLogger)

  def get(name: RepositoryName, locations: Set[String]) = {
    Get.get(baseDir, passphrase, progress)(name, locations)
  }

  def downloadLockfileLocations(newRequirements: Set[Requirement], lockfile: Lockfile) = {
    val newReqIds = newRequirements.map(_.id)
    InternalLockfileWrapper.locations(lockfile).foreach {
      case (name, id, maybeCommit, locations) =>
        if (!newReqIds(id)) {
          maybeCommit match {
            case Some(commit) =>
              val repository = new GitRepository(baseDir, name)
              if (!(repository.exists && repository.hasCommit(commit))) {
                get(name, locations)
              }
            case None => //pass
          }
        }
    }
  }

  def downloadLocations(searchResults: Set[SearchResult]) = {
    searchResults.foreach {
      case searchResult: ImportSearchResult => //pass
      case searchResult: GitSearchResult =>
        val repository = new GitRepository(baseDir, searchResult.repository)
        if (!(repository.exists && repository.hasCommit(searchResult.commit))) {
          get(searchResult.repository, searchResult.locations.toSet)
        }
      case searchResult: SearchResult =>
        throw new Exception("Found a search result but expected either an import or a git search result: " + searchResult)
    }
  }

  def getContext(searchResults: Set[SearchResult]) = {
    searchResults.map {
      case searchResult: ImportSearchResult =>
        val hash = VariantMetadata.fromVariant(searchResult.variant).hash
        ResolutionResult(searchResult.variant.id, searchResult.repository, None, hash)
      case searchResult: GitSearchResult =>
        val hash = VariantMetadata.fromVariant(searchResult.variant).hash
        ResolutionResult(searchResult.variant.id, searchResult.repository, Some(searchResult.commit), hash)
      case searchResult: SearchResult =>
        throw new Exception("Found a search result but expected either an import or a git search result: " + searchResult)
    }
  }

  def ivyImport(org: String, name: String, revision: String, configurations: Set[String], ivy: _root_.org.apache.ivy.Ivy = defaultIvy, useScalaConvert: Boolean = true, forceImport: Boolean = false): Either[Set[IvyImportError], Set[SearchResult]] = {
    val existing = Ivy.getExisting(this)(org, name, revision, configurations)
    val doImport = forceImport || revision.endsWith("SNAPSHOT") || { //either force or snapshot, then always import 
      existing.isEmpty
    }

    if (doImport) {
      Ivy.ivyImport(adept, this, progress)(org, name, revision, ivy, useScalaConvert, forceImport) match {
        case Right(_) =>
          Right(Set.empty[SearchResult])
        case Left(errors) =>
          Left(errors)
      }
    } else Right(existing.toSet)
  }

  val defaultTimeout = {
    import scala.concurrent.duration._
    1.minute
  }

  val defaultExecutionContext = {
    scala.concurrent.ExecutionContext.global //TODO: we should probably have multiple different execution contexts for IO/disk/CPU bound operations
  }

  @deprecated("Will be renamed to list") //TODO: <- remove and update based on deprecation
  def search(term: String, constraints: Set[Constraint] = Set.empty, onlineTimeout: FiniteDuration = defaultTimeout, allowOffline: Boolean, alwaysIncludeImports: Boolean = false): Set[SearchResult] = {
    val onlineResults = {
      val onlineFuture = Search.onlineSearch(url)(term, constraints, defaultExecutionContext)
      if (allowOffline) {
        onlineFuture.recover {
          case e: IOException =>
            Set.empty[GitSearchResult]
          case e: AdeptHubRecoverableException =>
            Set.empty[GitSearchResult]
        }(defaultExecutionContext)
      } else {
        onlineFuture
      }
    }
    val offlineResults = adept.search(term, constraints)
    val importResults = Search.searchImport(importsDir, adept)(term, constraints)
    Search.mergeSearchResults(imports = importResults, offline = offlineResults, online = Await.result(onlineResults, onlineTimeout), alwaysIncludeImports)
  }

  def offlineResolve(requirements: Set[Requirement], inputContext: Set[ResolutionResult], overrides: Set[ResolutionResult] = Set.empty): Either[ResolveErrorReport, (ResolveResult, Lockfile)] = {
    val overriddenInputContext = GitLoader.applyOverrides(inputContext, overrides)
    val context = GitLoader.computeTransitiveContext(baseDir, overriddenInputContext, Some(importsDir))
    val overriddenContext = GitLoader.applyOverrides(context, overrides)
    val transitiveLocations = GitLoader.computeTransitiveLocations(baseDir, overriddenInputContext, overriddenContext, Some(importsDir))
    val commitsByRepo = overriddenContext.groupBy(_.repository).map { case (repo, values) => repo -> values.map(_.commit) }
    transitiveLocations.foreach { locations =>
      val mustGet = commitsByRepo(locations.name).exists { commit =>
        val repository = new GitRepository(baseDir, locations.name)
        commit match {
          case Some(commit) =>
            !(repository.exists && repository.hasCommit(commit))
          case None =>
            false
        }
      }
      if (mustGet) {
        if (locations.uris.nonEmpty) {
          get(locations.name, locations.uris)
        } else {
          throw new Exception("Cannot not clone/pull: " + locations.name + " because there are no locations to download from")
        }
      }
    }

    val providedVariants = Set() ++
      JavaVersions.getVariants(majorJavaVersion, minorJavaVersion)
    val providedRequirements = Set() +
      JavaVersions.getRequirement(majorJavaVersion, minorJavaVersion) ++
      ScalaBinaryVersionConverter.getRequirement(scalaBinaryVersion)

    val mergedRequirements = (requirements ++ providedRequirements) //easier now and for ever after if requirements are merged into one id, with a set of constraints
      .groupBy(_.id)
      .map {
        case (id, reqs) =>
          val constraints = reqs
            .flatMap(_.constraints)
            .groupBy(_.name)
            .map {
              case (name, constraints) =>
                Constraint(name, values = constraints.flatMap(_.values))
            }
          Requirement(id, constraints.toSet, reqs.flatMap(_.exclusions))
      }.toSet

    //    val scalaFixedInputContext = { //a fix to avoid imports messing up
    //      val (scalaContextValues, nonScalaContextValues) = overriddenContext.partition { c =>
    //        ScalaBinaryVersionConverter.isScalaLibrary(c.id)
    //      }

    //      //We need this fix right now because it is quite common that there are multiple variants of scala in imports and other places
    //      //TODO: Must find a better way to do this (merging similar ranking files ?)
    //      val sortedScalaContextValues = scalaContextValues.groupBy(_.id).map {
    //        case (id, values) =>
    //          val sorted = values.toSeq.sortBy { value =>
    //            val metadata = value.commit match {
    //              case Some(commit) =>
    //                val repository = new GitRepository(baseDir, value.repository)
    //                VariantMetadata.read(id, value.variant, repository, commit, checkHash = true).getOrElse(throw new Exception("Cannot read scala variant: " + value + " from " + importsDir))
    //              case None =>
    //                val repository = new Repository(importsDir, value.repository)
    //                VariantMetadata.read(id, value.variant, repository, checkHash = true).getOrElse(throw new Exception("Cannot read scala variant: " + value + " from " + importsDir))
    //            }
    //            val variant = metadata.toVariant(id)
    //
    //            VersionRank.getVersion(variant).getOrElse(throw new Exception("Could not get version from scala variant: " + variant))
    //          }
    //          sorted.last
    //      }
    //      println(sortedScalaContextValues.mkString("\n"))
    //      sortedScalaContextValues.toSet ++ nonScalaContextValues
    //    }

    val result = Resolve.offlineResolve(this)(mergedRequirements, inputContext, overriddenInputContext, overriddenContext = overriddenContext, providedVariants, overrides, Some(importsDir)).right.map {
      case resolveResult =>
        logger.debug(resolveResult.toString)
        val artifactMap = resolveResult.getResolvedVariants.flatMap {
          case (_, variant) =>
            variant.artifacts.map(VariantMetadata.fromVariant(variant).hash -> _)
        }.toMap
        val variantHashMap = overriddenContext.groupBy(_.variant)
        val lockfileArtifacts = artifactMap.flatMap {
          case (variantHash, artifact) =>
            val artifactHash = artifact.hash
            variantHashMap(variantHash).map { contextValue =>
              val metadata = contextValue.commit match {
                case Some(commit) =>
                  val repository = new GitRepository(baseDir, contextValue.repository)
                  ArtifactMetadata.read(artifactHash, repository, commit).getOrElse(throw new Exception("Could not read artifact metadata for: " + artifactHash + ": " + contextValue))
                case None =>
                  val repository = new Repository(importsDir, contextValue.repository)
                  ArtifactMetadata.read(artifactHash, repository).getOrElse(throw new Exception("Could not read artifact metadata for: " + artifactHash + ": " + contextValue))
              }
              val fallbackFilename = contextValue.variant.value
              InternalLockfileWrapper.newArtifact(artifact.hash, metadata.size.toInt, metadata.locations, artifact.attributes, artifact.filename.getOrElse(fallbackFilename))
            }
        }.toSet
        val lockfileContext = inputContext.flatMap { c =>
          resolveResult.getResolvedVariants.get(c.id).flatMap { variant =>
            if (c.id != variant.id) throw new Exception("Input context has a different ids than resolved results. Resolved: " + variant.id.value + ", context: " + c.id.value + ". Context: " + c)
            val resolvedHash = VariantMetadata.fromVariant(variant).hash
            if (c.variant != resolvedHash) throw new Exception("Input context has a different hash than resolved results. Resolved: " + resolvedHash.value + ", context: " + c.variant.value + ". Context: " + c)
            val locations = transitiveLocations.filter(_.name == c.repository).flatMap(_.uris)
            Some(InternalLockfileWrapper.newContext(info = variant.toString, variant.id, c.repository, locations, c.commit, c.variant))
          }
        }
        val lockfileRequirements = mergedRequirements.map { r =>
          InternalLockfileWrapper.newRequirement(r.id, r.constraints, r.exclusions)
        }
        resolveResult -> InternalLockfileWrapper.create(lockfileRequirements, lockfileContext, lockfileArtifacts)
    }
    result
  }

  def writeLockfile(lockfile: Lockfile, file: File) = {
    var fos: FileOutputStream = null
    try {
      var fos = new FileOutputStream(file)
      fos.write(InternalLockfileWrapper.toJsonString(lockfile).getBytes)
      fos.flush()
    } finally {
      if (fos != null) fos.close()
    }
  }

  def contribute() = {
    Contribute.contribute(url, baseDir, passphrase, progress, importsDir)
  }

}