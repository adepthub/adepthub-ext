package adept.ivy

import org.apache.ivy.core.resolve.IvyNode
import org.apache.ivy.core.module.descriptor.DependencyDescriptor
import org.eclipse.jgit.lib.ProgressMonitor
import adept.ext._
import adept.ext.models.Module
import adept.repository.Repository
import adept.repository.metadata._
import adept.repository.models._
import adept.resolution.models._
import adept.artifact.ArtifactCache
import adept.artifact.models._
import java.io.File
import adept.logging.Logging

object IvyImportResultInserter extends Logging {
  import IvyUtils._

  /**
   * Defaults to ranking per version
   *
   *  @returns files that must be added and files that removed
   */
  protected def useDefaultVersionRanking(id: Id, variant: VariantHash, repository: Repository): (Set[File], Set[File]) = {
    val rankId = RankingMetadata.DefaultRankId
    val hashes = RankingMetadata.read(id, rankId, repository).toSeq.flatMap(_.variants)
    val variants = (hashes :+ variant).flatMap { hash =>
      VariantMetadata.read(id, hash, repository, checkHash = true).map(_.toVariant(id))
    }
    val sortedVariants = VersionRank.getSortedByVersions(variants)
    Set(RankingMetadata(sortedVariants).write(id, rankId, repository)) -> Set.empty[File]
  }

  /**
   *  Insert Ivy Import results (variants, resolution results, ...) into corresponding Adept repositories.
   *  Automatically ranks variants according to useDefaultVersionRanking.
   */
  def insertAsResolutionResults(importDir: File, baseDirForCache: File, results: Set[IvyImportResult], progress: ProgressMonitor): Set[ResolutionResult] = {
    progress.beginTask("Applying exclusion(s)", results.size)
    var included = Set.empty[IvyImportResult]
    results.foreach { result =>
      var requirementModifications = Map.empty[Id, Set[Variant]]
      var currentExcluded = false
      progress.update(1)

      for {
        otherResult <- results
        ((variantId, requirementId), excludeRules) <- result.excludeRules
        (excludeRuleOrg, excludeRuleName) <- excludeRules
        if (matchesExcludeRule(excludeRuleOrg, excludeRuleName, otherResult.variant))
      } { //<-- NOTICE
        if (variantId == result.variant.id) {
          logger.debug("Variant: " + variantId + " add exclusion for " + requirementId + ":" + excludeRules)
          val formerlyExcluded = requirementModifications.getOrElse(requirementId, Set.empty[Variant])
          requirementModifications += requirementId -> (formerlyExcluded + otherResult.variant) //MUTATE!
        } else if (requirementId == result.variant.id) {
          logger.debug("Requirement will exclude: " + result.variant.id + " will be excluded because of: " + excludeRules)
          currentExcluded = true //MUTATE!
        } else {
          logger.debug("Ignoring matching exclusion on: " + result.variant.id + " is " + ((variantId, requirementId), excludeRules))
        }
      }
      val fixedResult = if (requirementModifications.nonEmpty) {
        val fixedRequirements = result.variant.requirements.map { requirement =>
          requirementModifications.get(requirement.id).map { excludedVariants =>
            logger.debug("Excluding: " + excludedVariants.map(_.id) + " on " + requirement.id + " in " + result.variant.id)
            requirement.copy(
              exclusions = requirement.exclusions ++ excludedVariants.map(_.id))
          }.getOrElse(requirement)
        }
        val fixedVariant = result.variant.copy(requirements = fixedRequirements)
        result.copy(variant = fixedVariant)
      } else {
        result
      }

      if (!currentExcluded) {
        included += fixedResult
      }
    }
    progress.endTask()

    //group by modules
    val modules = results.groupBy { result =>
      Module.getModuleHash(result.variant)
    }

    val grouped = included.groupBy(_.repository) //grouping to avoid multiple parallel operations on same repo
    progress.beginTask("Writing Ivy results to repo(s)", grouped.size)
    grouped.par.foreach { //NOTICE .par TODO: replace with something more optimized for IO not for CPU
      case (_, results) =>
        results.foreach { result =>
          val variant = result.variant
          val id = variant.id
          val repository = new Repository(importDir, result.repository)
          val variantMetadata = VariantMetadata.fromVariant(variant)

          val existingVariantMetadata = VariantMetadata.read(id, variantMetadata.hash, repository, checkHash = true)
          if (!existingVariantMetadata.isDefined) { //this variant exists already so skip it
            variantMetadata.write(id, repository)
            result.artifacts.foreach { artifact =>
              ArtifactMetadata.fromArtifact(artifact).write(artifact.hash, repository)
            }
            useDefaultVersionRanking(id, variantMetadata.hash, repository)
          }
        }
        progress.update(1)
    }
    progress.endTask()
    progress.beginTask("Converting versions to adept", grouped.size)

    val ivyResultsByVersions = results.groupBy { ivyResult =>
      val version = VersionRank.getVersion(ivyResult.variant).getOrElse(throw new Exception("Found an ivy result without version: " + ivyResult))
      (ivyResult.repository, ivyResult.variant.id, version)
    }

    def createResolutionResults(versionInfo: Set[(RepositoryName, Id, Version)]): (Set[RecoverableError], Set[ResolutionResult]) = {
      var foundResults = Set.empty[ResolutionResult]
      var errors = Set.empty[RecoverableError]
      versionInfo.foreach {
        case (targetName, targetId, targetVersion) =>
          ivyResultsByVersions.get((targetName, targetId, targetVersion)) match {
            case Some(foundVersionedResults) =>
              if (foundVersionedResults.size > 1) {
                throw new Exception("Found more than one variant matching version: " + (targetName, targetId, targetVersion) + ":\n" + foundVersionedResults.mkString("\n"))
              } else if (foundVersionedResults.size < 1) {
                errors += VersionNotFoundException(targetName, targetId, targetVersion)
              } else {
                val foundVersionResult = foundVersionedResults.head
                foundResults += ResolutionResult(targetId, targetName, None, VariantMetadata.fromVariant(foundVersionResult.variant).hash)
              }
            case None =>
              errors += VersionNotFoundException(targetName, targetId, targetVersion)
          }
      }
      errors -> foundResults
    }
    val all = Set() ++ grouped.par.flatMap { //NOTICE .par TODO: same as above (IO vs CPU)
      case (name, results) =>
        val repository = new Repository(importDir, name)
        val completedResults = results.flatMap { result =>
          val variant = result.variant
          val id = variant.id
          val variantMetadata = VariantMetadata.fromVariant(variant)
          val includedVersionInfo = result.versionInfo //ivy will exclude what should be excluded anyways so we can just use it directly here

          val (foundVersionErrors, allFoundVersionResults) = createResolutionResults(includedVersionInfo) //VersionRank.createResolutionResults(importDir, includedVersionInfo)
          //error "handling" aka log some warnings - it might be ok but we cannot really know for sure
          foundVersionErrors.foreach {
            case RepositoryNotFoundException(targetName, targetId, targetVersion) =>
              logger.warn("In: " + result.variant.id + " tried to find " + targetId + " version: " + targetVersion + " in repository: " + targetName + " but the repository was not there. Assuming it is an UNAPPLIED override (i.e. an override of a module which the source module does not actually depend on) so ignoring...")
              Set.empty[ResolutionResult]
            case VersionNotFoundException(targetName, targetId, targetVersion) =>
              logger.warn("In: " + result.variant.id + " tried to find " + targetId + " version: " + targetVersion + " in repository: " + targetName + " but that version was not there. Assuming it is an UNAPPLIED override (i.e. an override of a module which the source module does not actually depend on) so ignoring...")
              Set.empty[ResolutionResult]
          }

          val thisModuleResults: Set[ResolutionResult] = {
            val currentModuleHash = Module.getModuleHash(variant)
            val ivyResults = modules(currentModuleHash)
            ivyResults.map { ivyResult =>
              val ivyResultRepository = repository.name
              //verify that we are in the same repository (commits won't work if not)
              if (ivyResultRepository != repository.name) throw new Exception("IvyResult for: " + ivyResult.variant + " does not have same repository as module variant:" + variant + ": " + repository.name)
              val ivyResultId = ivyResult.variant.id

              val ivyResultHash = {
                //TODO: we must scan because we change the variants (adding exclusions). Instead of doing this we could have a map of hashes which means the same thing (hash1 == hash2)
                val candidates = VariantMetadata.listVariants(ivyResultId, repository).flatMap { hash =>
                  VariantMetadata.read(ivyResultId, hash, repository, checkHash = true).filter { metadata =>
                    Module.getModuleHash(metadata.toVariant(ivyResultId)) == currentModuleHash
                  }.map(hash -> _)
                }
                if (candidates.size == 1) {
                  val (hash, selectedCandidate) = candidates.head
                  hash
                } else throw new Exception("Did not find exactly one candidate with module hash: " + currentModuleHash + " in " + (ivyResultId, repository.dir.getAbsolutePath()) + ":\n" + candidates.mkString("\n"))
              }

              ResolutionResult(ivyResultId, ivyResultRepository, None, ivyResultHash)
            }
          }

          val currentResults = allFoundVersionResults ++
            thisModuleResults

          val resolutionResultsMetadata = ResolutionResultsMetadata(currentResults.toSeq)
          resolutionResultsMetadata.write(id, variantMetadata.hash, repository)
          currentResults
        }
        progress.update(1)
        completedResults
    }
    progress.endTask()

    progress.beginTask("Copying ivy files and writing info", grouped.size)
    def copy(src: File, dest: File) = {
      import java.io._
      var fos: FileOutputStream = null
      var fis: FileInputStream = null
      try {
        dest.getParentFile.mkdirs()
        fos = new FileOutputStream(dest)
        fis = new FileInputStream(src)
        fos.getChannel().transferFrom(fis.getChannel(), 0, Long.MaxValue);
      } finally {
        if (fos != null)
          fos.close();
        if (fis != null)
          fis.close();
      }
    }
    grouped.par.foreach { //NOTICE .par TODO: same as above (IO vs CPU)
      case (name, results) =>
        val repository = new Repository(importDir, name)
        results.foreach { result =>
          val id = result.variant.id
          val hash = VariantMetadata.fromVariant(result.variant).hash
          result.info.foreach { info =>
            info.write(id, hash, repository)
          }
          result.resourceFile.foreach { file =>
            val dest = new File(repository.getVariantHashDir(id, hash), file.getName) 
            copy(file, dest)
          }
          result.resourceOriginalFile.foreach { file =>
            val dest = new File(repository.getVariantHashDir(id, hash), file.getName) 
            copy(file, dest)
          }
        }
        progress.update(1)
    }
    progress.endTask()

    progress.beginTask("Copying files to Adept cache", grouped.size)
    grouped.par.foreach { //NOTICE .par TODO: same as above (IO vs CPU)
      case (_, results) =>
        for {
          result <- results
          artifact <- result.variant.artifacts
          (expectedHash, file) <- result.localFiles
          if artifact.hash == expectedHash
        } { //<- NOTICE
          if (file.isFile) //sometimes Ivy removes the files for an very unknown reason. We do not care though, we can always download it again....
            ArtifactCache.cache(baseDirForCache, file, expectedHash, artifact.filename.getOrElse(file.getName))
        }
        progress.update(1)
    }
    progress.endTask()
    all
  }
}
