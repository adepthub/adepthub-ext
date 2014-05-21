package adept.lockfile

private[adept] object InternalLockfileWrapper { //All of these visibility limitations sucks... but I guess it is easier to go from package visibility/private to public than the other way aroud
  import collection.JavaConverters._

  def requirements(lockfile: Lockfile) = {
    lockfile.requirements.asScala
  }

  def variants(lockfile: Lockfile) = {
    lockfile.variants.asScala
  }

  def artifacts(lockfile: Lockfile) = {
    lockfile.artifacts.asScala
  }

  def create(requirements: Set[LockfileRequirement], variants: Set[LockfileContext], artifacts:Set[LockfileArtifact]) = {
    new Lockfile(requirements.asJava, variants.asJava, artifacts.asJava)
  }

  def newContext(info: String, id: Id, repository: RepositoryName, locations: java.util.Set[RepositoryLocation], commit: Commit, hash: VariantHash) = {
    new LockfileContext(info, id, repository, locations, commit, hash)
  }
  
  
  def javaVariant(variantHash: adept.repository.models.VariantHash): VariantHash = new VariantHash(variantHash.value)
  
}

