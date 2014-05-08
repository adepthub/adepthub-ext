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

  def create(requirements: collection.mutable.Set[LockfileRequirement], variants: collection.mutable.Set[LockfileVariant], artifacts: collection.mutable.Set[LockfileArtifact]) = {
    new Lockfile(requirements.asJava, variants.asJava, artifacts.asJava)
  }

  def newVariant(info: String, id: Id, repository: RepositoryName, locations: java.util.Set[RepositoryLocation], commit: Commit, hash: VariantHash) = {
    new LockfileVariant(info, id, repository, locations, commit, hash)
  }
  
  
  def javaVariant(variantHash: adept.repository.models.VariantHash): VariantHash = new VariantHash(variantHash.value)
  
}

