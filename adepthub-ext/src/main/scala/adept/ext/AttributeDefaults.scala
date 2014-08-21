package adept.ext

object AttributeDefaults {
  // a module is a collection of Ids that constitute the same entity: same module-hash == same module
  val ModuleHashAttribute = "module-hash"
  val VersionAttribute = "version"
  val BinaryVersionAttribute = "binary-version"
  val MatureAttribute = "mature"
  val PrerelaseAttribute = "prerelease"
  val SnapshotAttribute = "snapshot"
}
