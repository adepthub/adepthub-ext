package adept.test

import adept.ivy.IvyImportResult
import adept.repository.{GitLoader, MemoryLoader}
import adept.repository.models.ContextValue
import adept.resolution.models.Requirement
import adept.resolution.resolver.models.ResolveResult
import org.apache.ivy.core.module.descriptor.ModuleDescriptor

case class TestDetails(id: String)
case class BenchmarkId(id: String) extends AnyVal {
  def &&(other: BenchmarkId) = {
    BenchmarkId(id + other.id)
  }
}
case class BenchmarkName(value: String)

object BenchmarkUtils {
  val Inserted = BenchmarkName("Inserted")
  val InsertAfterInserted = BenchmarkName("InsertAfterInserted")
  val IvyImport = BenchmarkName("Ivy-import")
  val Converted = BenchmarkName("Converted")
  val Loaded = BenchmarkName("Loaded")
  val Resolved = BenchmarkName("Resolved")
  val Verified = BenchmarkName("Verified")

  import scala.language.implicitConversions //it is OK we are in test configuration only

  //TODO: move all Ivy things including this one to some other project?
  implicit def convertIvyModule(ivyModule: ModuleDescriptor): BenchmarkId = {
    BenchmarkId(ivyModule.toString)
  }

  implicit def convertIvyModule(results: Set[IvyImportResult]): BenchmarkId = {
    BenchmarkId(results.toString())
  }

  implicit def convertRequirements(requirements: Set[Requirement]): BenchmarkId = {
    BenchmarkId(requirements.toString())
  }

  implicit def convertContext(context: Set[ContextValue]): BenchmarkId = {
    BenchmarkId(context.toString())
    
  }
  implicit def convertGitLoader(loader: GitLoader): BenchmarkId = {
    convertContext(loader.context)
  }

  implicit def convertMemGitLoader(loader: MemoryLoader): BenchmarkId = {
    BenchmarkId(loader.variants.toString())
  }

  implicit def convertResolveResult(resolveResult: ResolveResult): BenchmarkId = {
    BenchmarkId(resolveResult.toString)
  }
}

object Benchmarkers {
  //Use OutputUtils
  private[test] val nullBenchmarker = new Benchmarker {
    override def benchmark(name: BenchmarkName, timeSpentMillis: Long, hash: BenchmarkId)(
      implicit testDetails: TestDetails): Unit = {}
  }
  private[test] val systemErrBenchmarker = new Benchmarker {
    override def benchmark(name: BenchmarkName, timeSpentMillis: Long, hash: BenchmarkId)(
      implicit testDetails: TestDetails): Unit = {
      System.err.println("Completed task: '" + name.value + "' (" + testDetails.id + ") in " +
        (timeSpentMillis / 1000.0) + "s")
    }
  }
}

abstract class Benchmarker {
  def benchmark(name: BenchmarkName, timeSpentMillis: Long, hash: BenchmarkId)(
    implicit testId: TestDetails): Unit
}
