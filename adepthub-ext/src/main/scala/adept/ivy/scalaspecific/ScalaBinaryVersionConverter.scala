package adept.ivy.scalaspecific

import adept.ivy.IvyImportResult
import adept.resolution.models.Id
import adept.repository.models.RankId
import adept.repository.metadata.RankingMetadata
import adept.repository.models.RepositoryName
import adept.repository.models.VariantHash
import adept.ext.Version
import adept.resolution.models.Constraint
import adept.ext.AttributeDefaults;
import adept.logging.Logging

import org.apache.ivy.core.module.descriptor.ExcludeRule

object ScalaBinaryVersionConverter extends Logging {
  val ScalaBinaryVersionRegex = """(.*)_(\d\..*?)(/.*)?""".r

  val ScalaBinaryVersionVersionExtractRegex = """^(\d*)\.(\d*)\.(\d*).*$""".r

  def getScalaBinaryCompatibleVersion(version: String) = {
    val binaryVersions = version match {
      case ScalaBinaryVersionVersionExtractRegex(majorString, minorString, point) =>
        val major = majorString.toInt
        val minor = minorString.toInt
        if (major > 2) {
          Set(major + "." + minor) -> RankId(major + "." + minor)
        } else if (major == 2 && minor > 9) {
          Set(major + "." + minor) -> RankId(major + "." + minor)
        } else if (major == 2 && minor == 9) {
          val binaryVersions =
            if (point == "3") Set("2.9.0", "2.9.1", "2.9.1-1", "2.9.2", "2.9.3")
            else if (point == "2") Set("2.9.0", "2.9.1", "2.9.1-1", "2.9.2")
            else if (point == "1-1") Set("2.9.0", "2.9.1", "2.9.1-1")
            else if (point == "1") Set("2.9.0", "2.9.1")
            else if (point == "0") Set("2.9.0")
            else Set.empty
          binaryVersions -> RankId(major + "." + minor)
        } else if (major == 2 && minor < 9) {
          Set.empty -> RankingMetadata.DefaultRankId
        }
    }
    binaryVersions
  }

  def isScalaLibrary(id: Id) {
    scalaLibIds(id)
  }
  val scalaRepository = RepositoryName("org.scala-lang")
  val scalaLibIds = Set(
    Id("org.scala-lang/scala-library/config/compile"),
    Id("org.scala-lang/scala-library/config/default"),
    Id("org.scala-lang/scala-library/config/javadoc"),
    Id("org.scala-lang/scala-library/config/master"),
    Id("org.scala-lang/scala-library/config/provided"),
    Id("org.scala-lang/scala-library/config/runtime"),
    Id("org.scala-lang/scala-library/config/sources"),
    Id("org.scala-lang/scala-library/config/system"),
    Id("org.scala-lang/scala-library"))

  private[adept] def extractId(id: Id) = { //TODO: , exists: (RepositoryName, Id) => Boolean
    id.value match {
      case ScalaBinaryVersionRegex(newId, binaryVersion, rest) => //TODO: if exists(name, id) =>
        Id(newId + Option(rest).getOrElse(""))
      case _ => id
    }
  }

  private def convertIdsWithScalaBinaryVersion(name: RepositoryName, ids: Set[Id]) = {
    ids.map { id =>
      extractId(id)
    }
  }

  def convertVersionInfoWithScalaBinaryVersion(versionInfo: Set[(RepositoryName, Id, Version)]): Set[(RepositoryName, Id, Version)] = {
    versionInfo.map {
      case (name, id, version) =>
        (name, extractId(id), version)
    }
  }

  private def convertExcludeRulesWithScalaBinaryVersion(excludeRules: Map[(Id, Id), Set[(String, String)]]): Map[(Id, Id), Set[(String, String)]] = {
    excludeRules.map {
      case ((id1, id2), excludes) =>
        ((extractId(id1), extractId(id2)), excludes)
    }
  }

  def convertResultWithScalaBinaryVersion(ivyImportResult: IvyImportResult): IvyImportResult = {
    val (id, maybeBinaryVersion) = ivyImportResult.variant.id.value match {
      case ScalaBinaryVersionRegex(newId, binaryVersion, rest) =>
        Id(newId + Option(rest).getOrElse("")) -> Some(binaryVersion) //rest is null if nothing matches
      case _ => ivyImportResult.variant.id -> None
    }
    if (scalaLibIds(id)) {
      ivyImportResult.variant.attribute(AttributeDefaults.VersionAttribute)
      ivyImportResult
    } else {
      maybeBinaryVersion match {
        case Some(binaryVersion) =>
          // We cannot be this strict because of sometimes you have a Id that does not depend on anything but still has _2.10  in its Id etc etc
          //        val convertible = ivyImportResult.versionInfo.exists {
          //          case (name, id, version) if exists(name, id) =>
          //            if (!version.asBinaryVersion.contains(binaryVersion))
          //              logger.warn("While converting using Scala binary versions we got a version (" + version + ") which does not contain the binary version expected: " + binaryVersion)
          //            name == scalaRepository && id == scalaLibId
          //        }
          val (scalaLibReqs, noScalaLibReqs) = ivyImportResult.variant.requirements.partition(r => scalaLibIds.contains(r.id))
          val hasAlreadyBinaryVersion = scalaLibReqs.exists(_.constraints.exists(_.name == AttributeDefaults.BinaryVersionAttribute))
          if (!hasAlreadyBinaryVersion) {
            val newReqs = noScalaLibReqs.map { requirement =>
              val newReqId = requirement.id.value match {
                case ScalaBinaryVersionRegex(newId, binaryVersion, rest) => //TODO: we should check if this one exists
                  Id(newId + Option(rest).getOrElse(""))
                case _ => requirement.id
              }
              requirement.copy(id = newReqId)
            } ++ scalaLibReqs.map { requirement =>
              val newConstraints = requirement.constraints + Constraint(AttributeDefaults.BinaryVersionAttribute, Set(binaryVersion))
              requirement.copy(constraints = newConstraints)
            }
            val newVariant = ivyImportResult.variant.copy(
              id = id,
              requirements = newReqs)
            logger.debug("Adding scala library binary version: " + binaryVersion + " on ivy import of: " + ivyImportResult.variant)
            ivyImportResult.copy(
              variant = newVariant,
              excludeRules = convertExcludeRulesWithScalaBinaryVersion(ivyImportResult.excludeRules),
              extendsIds = convertIdsWithScalaBinaryVersion(ivyImportResult.repository, ivyImportResult.extendsIds),
              versionInfo = convertVersionInfoWithScalaBinaryVersion(ivyImportResult.versionInfo))
          } else {
            ivyImportResult
          }
        case None =>
          val newVariant = ivyImportResult.variant.copy(
            id = id,
            requirements = ivyImportResult.variant.requirements.map { requirement =>
              val newReqId = requirement.id.value match {
                case ScalaBinaryVersionRegex(newId, _, rest) => //TODO: we should check if this one exists
                  Id(newId + Option(rest).getOrElse(""))
                case _ => requirement.id
              }
              val newExclusions = requirement.exclusions
              requirement.copy(id = newReqId)
            })
          ivyImportResult.copy(
            variant = newVariant,
            excludeRules = convertExcludeRulesWithScalaBinaryVersion(ivyImportResult.excludeRules),
            extendsIds = convertIdsWithScalaBinaryVersion(ivyImportResult.repository, ivyImportResult.extendsIds),
            versionInfo = convertVersionInfoWithScalaBinaryVersion(ivyImportResult.versionInfo))
      }
    }
  }
}
