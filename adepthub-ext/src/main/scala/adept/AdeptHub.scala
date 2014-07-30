package adept

import java.io.File
import adept.logging.Logging
import adept.resolution.models.Id
import scala.concurrent.duration.FiniteDuration
import adept.resolution.models.Constraint
import adept.resolution.models.Requirement
import adept.repository.models.RepositoryName
import adept.resolution.resolver.models.ResolveResult
import adept.repository.models.ContextValue
import adept.repository.Repository
import adept.repository.GitRepository
import adept.repository.metadata.VariantMetadata
import adept.ivy.IvyUtils
import org.eclipse.jgit.lib.ProgressMonitor
import adept.lockfile.{LockfileConverters, Lockfile}
import adept.ext.AttributeDefaults
import adept.repository.GitLoader
import net.sf.ehcache.CacheManager
import adept.models.{ImportSearchResult, GitSearchResult, SearchResult}
import scala.concurrent.Await
import adept.repository.metadata.ArtifactMetadata
import adept.ivy.IvyImportError
import java.io.IOException
import adept.ext.models.ResolveErrorReport
import adept.ext.models.Module
import adept.ext.VersionRank
import adept.resolution.models.Variant

object AdeptHub {

  def highestVersionedSearchResults(searchResults: Set[SearchResult]) = {
    searchResults
      .groupBy(r => VersionRank.getVersion(r.variant))
      .toVector
      .sortBy { case (version, _) => version}
      .lastOption.flatMap {
      case (maybeVersion, results) =>
        maybeVersion.map { version =>
          version -> results
        }
    }
  }

  def getUniqueModule(expression: String, searchResults: Set[SearchResult]): Either[String,
    (String, Set[Variant])] = {
    val modules = Module.getModules(searchResults.map(_.variant))
    if (modules.size == 0) {
      val msg = s"Could not find any variants matching '$expression'."
      Left(msg)
    } else if (modules.size > 1) {
      val msg = s"Found more than one module matching '$expression'.\n" +
        "Results are:\n" + modules.map {
        case ((base, _), variants) =>
          base + "\n" + variants.map(variant => VersionRank.getVersion(variant).map(_.value).getOrElse(
            variant.toString)).map("\t" + _).mkString("\n")
      }.mkString("\n")
      Left(msg)
    } else {
      val ((baseIdString, _), variants) = modules.head
      Right((baseIdString, variants))
    }
  }

  def searchResultsToContext(searchResults: Set[SearchResult]) = {
    searchResults.map {
      case searchResult: ImportSearchResult =>
        val hash = VariantMetadata.fromVariant(searchResult.variant).hash
        ContextValue(searchResult.variant.id, searchResult.repository, None, hash)
      case searchResult: GitSearchResult =>
        val hash = VariantMetadata.fromVariant(searchResult.variant).hash
        ContextValue(searchResult.variant.id, searchResult.repository, Some(searchResult.commit), hash)
      case searchResult: SearchResult =>
        throw new Exception("Found a search result but expected either an import or a git search result: " +
          searchResult)
    }
  }

  def variantsAsConfiguredRequirements(potentialVariants: Set[Variant], baseIdString: String,
                                       confs: Set[String]) = {
    val configuredIds = confs.map(IvyUtils.withConfiguration(Id(baseIdString), _))
    potentialVariants.filter { variant =>
      configuredIds(variant.id)
    }.map { variant =>
      Requirement(variant.id, Set.empty[Constraint], Set.empty)
    }
  }

  def newLockfileRequirements(newRequirements: Set[Requirement], lockfile: Lockfile) = {
    val newReqIds = newRequirements.map(_.id)
    val requirements = newRequirements ++ LockfileConverters.requirements(lockfile).filter { req =>
      //remove old reqs which are overwritten
      !newReqIds(req.id)
    }
    requirements
  }

  def newLockfileContext(context: Set[ContextValue], lockfile: Lockfile) = {
    if (lockfile.getContext == null) {
      throw new IllegalArgumentException("lockfile.context is null")
    }
    val newContextIds = context.map(_.id)
    context ++ LockfileConverters.context(lockfile).filter { c =>
      //remove old context values which are overwritten
      !newContextIds(c.id)
    }
  }

  def baseId(variants: Set[Variant]) = {
    variants.map(_.id.value).reduce(_ intersect _)
  }

  def renderSearchResults(searchResults: Set[SearchResult], term: String, constraints: Set[Constraint] =
  Set.empty) = {
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
        base -> (variants.map(variant => VersionRank.getVersion(variant).map(_.value).getOrElse(
          variant.toString)).map("\t" + _).mkString("\n") + locationString)
    }
    baseVersions.groupBy { case (base, _) => base}.map {
      case (base, grouped) =>
        val versions = grouped.map(_._2)
        base + "\n" + versions.mkString("\n")
    }.mkString("\n")
  }

  def renderErrorReport(result: ResolveResult, requirements: Set[Requirement], context: Set[ContextValue],
                        overrides: Set[ContextValue] = Set.empty) = {
    val state = result.state
    val msg = if (state.isUnderconstrained) {
      result.toString + "\n" +
        "The graph is under-constrained (there are 2 or more variants matching the same ids). This is" +
        " likely due to ivy imports. This will be fixed soon, but until then: contributing/uploading your" +
        " ivy imports will fix this." + "\n"
    } else {
      result.toString
    }
    ResolveErrorReport(msg, result)
  }

}

class AdeptHub(val baseDir: File, val importsDir: File, val cacheManager: CacheManager, val url:
               String = Defaults.url, val passphrase: Option[String] = None, val onlyOnline: Boolean = false,
               val progress: ProgressMonitor = Defaults.progress) extends Adept(baseDir, cacheManager,
  passphrase) with Logging {
  //TODO: make logging configurable
  def defaultIvy = Defaults.ivy

  def get(name: RepositoryName, locations: Set[String]) = {
    Get.get(baseDir, passphrase)(name, locations)
  }

  def downloadLockfileLocations(newRequirements: Set[Requirement], lockfile: Lockfile) = {
    val newReqIds = newRequirements.map(_.id)
    val allLocations = LockfileConverters.locations(lockfile)
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
        throw new Exception("Found a search result but expected either an import or a git search result: " +
          searchResult)
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

  def ivyImport(org: String, name: String, revision: String, configurations: Set[String],
                scalaBinaryVersion: String, ivy: _root_.org.apache.ivy.Ivy = defaultIvy,
                useScalaConvert: Boolean = true, forceImport:
  Boolean = false): Either[Set[IvyImportError], Set[SearchResult]] = {
    val existing = Ivy.getExisting(this, scalaBinaryVersion)(org, name, revision, configurations)
    val doImport = forceImport || revision.endsWith("SNAPSHOT") || {
      //either force or snapshot, then always import
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
    //TODO: we should probably have multiple different execution contexts for IO/disk/CPU bound operations
    scala.concurrent.ExecutionContext.global
  }

  def search(partialId: String, constraints: Set[Constraint] = Set.empty, onlineTimeout: FiniteDuration =
  defaultTimeout, allowLocalOnly: Boolean = false, alwaysIncludeImports: Boolean = false):
  Set[SearchResult] = {
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
    Search.mergeSearchResults(imports = importResults, offline = offlineResults, online = Await.result(
      onlineResults, onlineTimeout), alwaysIncludeImports)
  }

  def resolve(requirements: Set[Requirement], inputContext: Set[ContextValue], overrides: Set[ContextValue] =
  Set.empty, providedVariants: Set[Variant] = Set.empty): Either[ResolveResult, (ResolveResult, Lockfile)] = {
    val overriddenInputContext = GitLoader.applyOverrides(inputContext, overrides)
    val context = GitLoader.computeTransitiveContext(baseDir, overriddenInputContext, Some(importsDir))
    //apply overrides again in case something transitive needs to be overridden
    val overriddenContext = GitLoader.applyOverrides(context, overrides)
    val transitiveLocations = GitLoader.computeTransitiveLocations(baseDir, overriddenInputContext,
      overriddenContext, Some(importsDir))
    val commitsByRepo = overriddenContext.groupBy(_.repository).map { case (repo, values) => repo ->
      values.map(_.commit)}
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
          throw new Exception("Cannot not clone/pull: " + locations.name +
            " because there are no locations to download from")
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

    //easier now and for ever after if requirements are merged into one id, with a set of constraints
    val mergedRequirements = requirements
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

    val result = localResolve(mergedRequirements, inputContext, overriddenInputContext, overriddenContext =
      overriddenContext, providedVariants, overrides, Set(importsDir)).right.map {
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
                  ArtifactMetadata.read(artifactHash, repository, commit).getOrElse(throw new Exception(
                    "Could not read artifact metadata for: " + artifactHash + ": " + contextValue))
                case None =>
                  val repository = new Repository(importsDir, contextValue.repository)
                  ArtifactMetadata.read(artifactHash, repository).getOrElse(throw new Exception(
                    "Could not read artifact metadata for: " + artifactHash + ": " + contextValue))
              }
              val fallbackFilename = contextValue.variant.value
              LockfileConverters.newArtifact(artifact.hash, metadata.size.toInt, metadata.locations,
                artifact.attributes, artifact.filename.getOrElse(fallbackFilename))
            }
        }.toSet
        val lockfileContext = inputContext.flatMap { c =>
          resolveResult.getResolvedVariants.get(c.id).flatMap { variant =>
            if (c.id != variant.id) throw new Exception(
              "Input context has a different ids than resolved results. Resolved: " + variant.id.value +
                ", context: " + c.id.value + ". Context: " + c)
            val resolvedHash = VariantMetadata.fromVariant(variant).hash
            if (c.variant != resolvedHash) throw new Exception(
              "Input context has a different hash than resolved results. Resolved: " + resolvedHash.value +
                ", context: " + c.variant.value + ". Context: " + c)
            val locations = transitiveLocations.filter(_.name == c.repository).flatMap(_.uris)
            Some(LockfileConverters.newContext(variant.toString, variant.id, c.repository, locations,
              c.commit, c.variant))
          }
        }
        val lockfileRequirements = mergedRequirements.map { r =>
          LockfileConverters.newRequirement(r.id, r.constraints, r.exclusions)
        }
        resolveResult -> LockfileConverters.create(lockfileRequirements, lockfileContext, lockfileArtifacts)
    }
    result
  }

  def contribute() = {
    Contribute.contribute(url, baseDir, passphrase, progress, importsDir)
  }
}
