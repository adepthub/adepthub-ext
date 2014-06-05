

import scala.util.Failure
import scala.util.Success
import adept.ext.JavaVersions

private object APIExample extends App { //TODO: move to other project
  import adept.ext.AttributeDefaults
  import adept.lockfile.Lockfile
  import adept.resolution.models._
  import adept.{ Defaults, AdeptHub }
  import java.io.File

  val cacheManager = net.sf.ehcache.CacheManager.create() //cachemanager can be kept in memory to speed up later resolutions
  val adepthub = new AdeptHub(baseDir = Defaults.baseDir, importsDir = new File("adept-imports"), cacheManager = cacheManager)

  //generic search (no constraints)
  {
    val term = "typesafe"
    val searchResults = adepthub.search(term)
    println(searchResults)
  }

  //search a module and render results
  {
    val term = "com.typesafe.play/play-json" + Id.Sep //<- hit only exact
    val searchResults = adepthub.search(term)
    println(AdeptHub.renderSearchResults(searchResults, term))
  }

  //search a module and render results for a specific version
  {
    val term = "com.typesafe.play/play-json" + Id.Sep //<- hit only exact
    val searchResults = adepthub.search(term, Set(Constraint(AttributeDefaults.VersionAttribute, Set("2.2.0"))))
    println(AdeptHub.renderSearchResults(searchResults, term))
  }

  //End-to-end - for only resolution see resolve method below
  {
    //INPUT START
    val term = "com.typesafe.play/play-json" + Id.Sep //<-- specified string
    val constraints = Set(Constraint(AttributeDefaults.VersionAttribute, Set("2.2.0"))) //<-- constrain to only 2.2.0
    val lockfileFile = new File("compile.adept") //<-- lockfile file (could be anything, but often we map the name to the conf of OUR module)
    val confs = Set("compile", "master") //<-- desired confs of our future requirements (compile has the dependencies, master has the artifacts)
    //INPUT END

    val highestSearchResultsOnly = {
      val allSearchResults = adepthub.search( //<-- search for all matching term
        term,
        constraints,
        allowLocalOnly = true) //<-- change to false to force online searches - when it is true, Adept will be able to resolve if metadata is local
      AdeptHub
        .highestVersionedSearchResults(allSearchResults) //<-- get highest
        .toSeq //we loose type-info on toSet :(
        .flatMap { //flatten
          case (version, searchResults) =>
            searchResults
        }.toSet
    }

    val uniqueModule = AdeptHub
      .getUniqueModule(term, highestSearchResultsOnly) //<-- get the UNIQUE module matching this term/constraints...
      .fold(errorMsg => Failure(new Exception(errorMsg)), res => Success(res)) //<-- convert to Try to use for-expr later (not required/can be implented differently)
    val lockfile = Lockfile.read(lockfileFile)

    def resolve(baseIdString: String, variants: Set[Variant]) = {
      //1) Use configurations to get the variants needed (OPTIONAL: could also create requirements)
      val newRequirements = AdeptHub.variantsAsConfiguredRequirements(variants, baseIdString, confs)
      //2) Compute all requirements including those in from the lockfile
      val requirements = AdeptHub.newLockfileRequirements(newRequirements, lockfile)
      //3) Compute context based on search results
      val inputContext = AdeptHub.newLockfileContext(AdeptHub.searchResultsToContext(highestSearchResultsOnly), lockfile)
      //4) Generate overrides to make sure nothing transitively overrides our context (OPTIONAL: can be skipped) 
      val overrides = inputContext

      //5) Make sure chosen search results are local/download if not
      adepthub.downloadLocations(highestSearchResultsOnly)

      //6) Make sure all metadata from lockfile is local/download if not
      adepthub.downloadLockfileLocations(newRequirements, lockfile)

      //7) Create Java system variants (required by all Java)  
      val (majorJavaVersion, minorJavaVersion) = JavaVersions.getMajorMinorVersion(this.getClass, this.getClass().getClassLoader())
      val javaVariants = JavaVersions.getVariants(majorJavaVersion, minorJavaVersion)

      //8) Resolve
      adepthub.resolve(
        requirements = requirements,
        inputContext = inputContext,
        overrides = overrides,
        providedVariants = javaVariants)
        .fold( //<-- convert to Try to use for-expr later (not required/can be implented differently)
          error => Failure(new Exception(error.toString)),
          {
            case (result, newLockfile) =>
              Success(result -> newLockfile)
          })
    }

    //Actual resolve:
    val result = for {
      (baseIdString, variants) <- uniqueModule
      (result, newLockfile) <- resolve(baseIdString, variants)
    } yield (result, newLockfile) //returns the same results to handle error below

    //Print failures (if any)
    result.failed.foreach { ex =>
      println("FAILED!") //<-- should not happen (hopefully)!
      println(ex.getMessage())
    }

    //Print result (if any):
    result.foreach {
      case (result, newLockfile) =>
        println(result) //prints an internal representation of the state with (large) graph - not something the user wants to see (see https://github.com/adepthub/adepthub-ext/issues/13) 
        adepthub.writeLockfile(lockfile, lockfileFile) //update lockfile
        println("Wrote: " + lockfileFile)
    }
  }

  cacheManager.shutdown() //must shutdown to join cachemanager threads

}