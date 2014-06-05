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
import adept.ext.models.ResolveErrorReport
import adepthub.models.SearchResult
import adept.ext.models.Module
import adept.ext.VersionRank
import adept.resolution.models.Variant
import scala.util.Success
import scala.util.Failure

object AdeptHub {
  def getUniqueModule(expression: String, searchResults: Set[SearchResult]): Either[String, (String, Set[Variant])] = {
    val modules = Module.getModules(searchResults.map(_.variant))
    if (modules.size == 0) {
      val msg = s"Could not find any variants matching '$expression'."
      Left(msg)
    } else if (modules.size > 1) {
      val msg = s"Found more than one module matching '$expression'.\n" +
        "Results are:\n" + modules.map {
          case ((base, _), variants) =>
            base + "\n" + variants.map(variant => VersionRank.getVersion(variant).map(_.value).getOrElse(variant.toString)).map("\t" + _).mkString("\n")
        }.mkString("\n")
      Left(msg)
    } else {
      val ((baseIdString, moduleHash), variants) = modules.head
      Right((baseIdString, variants))
    }
  }

  def searchResultsToContext(searchResults: Set[SearchResult]) = {
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

  def variantsAsConfiguredRequirements(potentialVariants: Set[Variant], baseIdString: String, confs: Set[String]) = {
    val configuredIds = confs.map(IvyUtils.withConfiguration(Id(baseIdString), _))
    potentialVariants.filter { variant =>
      configuredIds(variant.id)
    }.map { variant =>
      Requirement(variant.id, Set.empty[Constraint], Set.empty)
    }
  }

  def newLockfileRequirements(newRequirements: Set[Requirement], lockfile: Lockfile) = {
    val newReqIds = newRequirements.map(_.id)
    val requirements = newRequirements ++ (InternalLockfileWrapper.requirements(lockfile).filter { req =>
      //remove old reqs which are overwritten
      !newReqIds(req.id)
    })
    requirements
  }

  def newLockfileContext(context: Set[ResolutionResult], lockfile: Lockfile) = {
    val newContextIds = context.map(_.id)
    context ++ (InternalLockfileWrapper.context(lockfile).filter { c =>
      //remove old context values which are overwritten
      !newContextIds(c.id)
    })
  }

  def baseId(variants: Set[Variant]) = {
    variants.map(_.id.value).reduce(_ intersect _)
  }

  def renderSearchResults(searchResults: Set[SearchResult], term: String, constraints: Set[Constraint] = Set.empty) = {
    val modules = searchResults.groupBy(_.variant.attribute(AttributeDefaults.ModuleHashAttribute)).map {
      case (moduleAttribute, searchResults) =>
        val variants = searchResults.map(_.variant)
        val local = searchResults.exists {
          case gitSearchResult: GitSearchResult => gitSearchResult.isLocal
          case _: ImportSearchResult => true
          case _ => false
        }
        val imported = searchResults.exists {
          case _: ImportSearchResult => true
          case _: GitSearchResult => false
          case _ => false
        }

        val base = baseId(variants)
        (base, moduleAttribute, imported, local) -> variants
    }.toSet
    val baseVersions = modules.map {
      case ((base, _, imported, local), variants) =>
        val locationString = if (imported) " (imported)" else if (!local) " (AdeptHub)" else " (local)"
        base -> (variants.map(variant => VersionRank.getVersion(variant).map(_.value).getOrElse(variant.toString)).map("\t" + _).mkString("\n") + locationString)
    }
    baseVersions.groupBy { case (base, _) => base }.map {
      case (base, grouped) =>
        val versions = grouped.map(_._2)
        base + "\n" + versions.mkString("\n")
    }.mkString("\n")
  }

  def renderErrorReport(result: ResolveResult, requirements: Set[Requirement], context: Set[ResolutionResult], overrides: Set[ResolutionResult] = Set.empty) = {
    val state = result.state
    val msg = (
      if (state.isUnderconstrained) {
        result.toString + "\n" +
          "The graph is under-constrained (there are 2 or more variants matching the same ids). This is likely due to ivy imports. This will be fixed soon, but until then: contributing/uploading your ivy imports will fix this." + "\n"
      } else {
        result.toString
      })
    ResolveErrorReport(msg, result)
  }

}

class AdeptHub(val baseDir: File, val importsDir: File, val cacheManager: CacheManager, val url: String = Defaults.url, val passphrase: Option[String] = None, val onlyOnline: Boolean = false, val progress: ProgressMonitor = Defaults.progress) extends Adept(baseDir, cacheManager, passphrase, progress) with Logging { //TODO: make logging configurable
  def defaultIvy = Defaults.ivy

  def get(name: RepositoryName, locations: Set[String]) = {
    Get.get(baseDir, passphrase)(name, locations)
  }

  def downloadLockfileLocations(newRequirements: Set[Requirement], lockfile: Lockfile) = {
    val newReqIds = newRequirements.map(_.id)
    val allLocations = InternalLockfileWrapper.locations(lockfile)
    val required = allLocations.flatMap {
      case (name, id, maybeCommit, locations) =>
        if (!newReqIds(id)) {
          maybeCommit match {
            case Some(commit) =>
              val repository = new GitRepository(baseDir, name)
              if (!(repository.exists && repository.hasCommit(commit))) {
                Some(name -> locations)
              } else None
            case None =>
              None
          }
        } else None
    }
    if (required.nonEmpty) {
      progress.beginTask("Fetching lockfile metadata", required.size)
      required.foreach {
        case (name, locations) =>
          get(name, locations)
          progress.update(1)
      }
      progress.endTask()
    }
  }

  def downloadLocations(searchResults: Set[SearchResult]) = {
    val required = searchResults.flatMap {
      case searchResult: ImportSearchResult => //pass
        None
      case searchResult: GitSearchResult =>
        val repository = new GitRepository(baseDir, searchResult.repository)
        if (!(repository.exists && repository.hasCommit(searchResult.commit))) {
          Some(searchResult.repository -> searchResult.locations.toSet)
        } else None
      case searchResult: SearchResult =>
        throw new Exception("Found a search result but expected either an import or a git search result: " + searchResult)
    }
    if (required.nonEmpty) {
      progress.beginTask("Fetching search result metadata", required.size)
      required.foreach {
        case (name, locations) =>
          get(name, locations)
          progress.update(1)
      }
      progress.endTask()
    }
  }

  def ivyImport(org: String, name: String, revision: String, configurations: Set[String], scalaBinaryVersion: String, ivy: _root_.org.apache.ivy.Ivy = defaultIvy, useScalaConvert: Boolean = true, forceImport: Boolean = false): Either[Set[IvyImportError], Set[SearchResult]] = {
    val existing = Ivy.getExisting(this, scalaBinaryVersion)(org, name, revision, configurations)
    val doImport = forceImport || revision.endsWith("SNAPSHOT") || { //either force or snapshot, then always import 
      existing.isEmpty
    }

    if (doImport) {
      Ivy.ivyImport(this)(org, name, revision, ivy, useScalaConvert, forceImport) match {
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

  def search(partialId: String, constraints: Set[Constraint] = Set.empty, onlineTimeout: FiniteDuration = defaultTimeout, allowLocalOnly: Boolean = false, alwaysIncludeImports: Boolean = false): Set[SearchResult] = {
    val onlineResults = {
      val onlineFuture = Search.onlineSearch(url)(partialId, constraints, defaultExecutionContext)
      if (allowLocalOnly) {
        onlineFuture.recover {
          case e: IOException =>
            logger.info("Could not get results from AdeptHub.com.")
            Set.empty[GitSearchResult]
          case e: AdeptHubRecoverableException =>
            logger.info("Could not get results from AdeptHub.com.")
            Set.empty[GitSearchResult]
        }(defaultExecutionContext)
      } else {
        onlineFuture
      }
    }
    val offlineResults = localSearch(partialId, constraints)
    val importResults = Search.searchImports(this)(partialId, constraints)
    Search.mergeSearchResults(imports = importResults, offline = offlineResults, online = Await.result(onlineResults, onlineTimeout), alwaysIncludeImports)
  }

  def resolve(requirements: Set[Requirement], inputContext: Set[ResolutionResult], scalaBinaryVersion: String, majorJavaVersion: Int, minorJavaVersion: Int, overrides: Set[ResolutionResult] = Set.empty): Either[ResolveResult, (ResolveResult, Lockfile)] = {
    val overriddenInputContext = GitLoader.applyOverrides(inputContext, overrides)
    val context = GitLoader.computeTransitiveContext(baseDir, overriddenInputContext, Some(importsDir))
    val overriddenContext = GitLoader.applyOverrides(context, overrides) //apply overrides again in case something transitive needs to be overridden
    val transitiveLocations = GitLoader.computeTransitiveLocations(baseDir, overriddenInputContext, overriddenContext, Some(importsDir))
    val commitsByRepo = overriddenContext.groupBy(_.repository).map { case (repo, values) => repo -> values.map(_.commit) }
    val required = transitiveLocations.flatMap { locations =>
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
          Some(locations.name -> locations.uris)
        } else {
          throw new Exception("Cannot not clone/pull: " + locations.name + " because there are no locations to download from")
        }
      } else None
    }
    if (required.nonEmpty) {
      progress.beginTask("Fetching transitive metadata", required.size)
      required.foreach {
        case (name, locations) =>
          get(name, locations)
          progress.update(1)
      }
      progress.endTask()
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

    val result = localResolve(mergedRequirements, inputContext, overriddenInputContext, overriddenContext = overriddenContext, providedVariants, overrides, Set(importsDir)).right.map {
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

  def contribute() = {
    Contribute.contribute(url, baseDir, passphrase, progress, importsDir)
  }

}