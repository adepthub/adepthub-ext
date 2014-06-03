package adept.sbt.commands

import sbt.State
import java.io.File
import adept.AdeptHub
import adept.ivy.scalaspecific.ScalaBinaryVersionConverter
import adept.resolution.models._
import adepthub.models._
import adept.repository.metadata._
import adept.repository.models._
import adept.ext.models.Module
import adept.ext.VersionRank
import adept.ivy.IvyUtils
import adept.lockfile.Lockfile
import adept.lockfile.InternalLockfileWrapper
import adept.sbt.AdeptDefaults
import adept.ext.AttributeDefaults
import adept.sbt.SbtUtils
import adept.sbt.AdeptKeys
import adept.sbt.AdeptUtils
import scala.util.Success
import scala.util.Failure
import adept.sbt.UserInputException

object SearchCommand {
  import sbt.complete.DefaultParsers._
  import sbt.complete._
  def using(adepthub: AdeptHub) = {
    ((token("search") ~> (Space ~> NotSpaceClass.+).+).map { args =>
      new SearchCommand(args.map(_.mkString), adepthub)
    })

  }
}

class SearchCommand(args: Seq[String], adepthub: AdeptHub) extends AdeptCommand {
  def execute(state: State): State = {
    val logger = state.globalLogging.full

    val term = args.head
    val constraints = Set.empty[Constraint]
    val searchResults = adepthub.search(term, constraints, allowOffline = true)
    val modules = searchResults.groupBy(_.variant.attribute(AttributeDefaults.ModuleHashAttribute)).map {
      case (moduleAttribute, searchResults) =>
        val variants = searchResults.map(_.variant)
        val offline = searchResults.exists{
          case gitSearchResult: GitSearchResult => gitSearchResult.isOffline
          case _: ImportSearchResult => true
          case _ => false
        }
        val imported = searchResults.exists{
          case _: ImportSearchResult => true
          case _: GitSearchResult => false
          case _ => false
        }
        
        val base = variants.map(_.id.value).reduce(_ intersect _)
        (base, moduleAttribute, imported, offline) -> variants
    }
    val msg = modules.map {
      case ((base, _, imported, offline), variants) =>
        val locationString = if (imported) " (imported)" else if (!offline) " (AdeptHub)" else " (offline)" 
        base + "\n" + variants.map(variant => VersionRank.getVersion(variant).map(_.value).getOrElse(variant.toString)).map("\t" + _).mkString("\n") + locationString
    }.mkString("\n")

    logger.info(msg)
    state
  }
}
