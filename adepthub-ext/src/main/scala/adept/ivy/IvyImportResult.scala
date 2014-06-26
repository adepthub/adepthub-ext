package adept.ivy

import adept.resolution.models._
import adept.artifact.models._
import adept.repository.models._
import adept.ext._
import java.io.File
import adept.repository.metadata.InfoMetadata

//Contains everything needed to be able to import from Ivy to Adept
case class IvyImportResult(variant: Variant, artifacts: Set[Artifact], localFiles: Map[ArtifactHash, File],
                           repository: RepositoryName, versionInfo: Set[(RepositoryName, Id, Version)],
                           excludeRules: Map[(Id, Id), Set[(String, String)]], extendsIds: Set[Id],
                           info: Option[InfoMetadata], resourceFile: Option[File], resourceOriginalFile: Option[File])

case class VersionInfo(name: RepositoryName, id: Id, version: Version) //TODO: use this one in IvyImportResult as well!
case class AdeptExclude(on: Id, requirement: Id)
case class IvyExclude(org: String, name: String)
case class AdeptExcludeMapping(adeptExclude: AdeptExclude, ivyExcludes: Set[IvyExclude])

