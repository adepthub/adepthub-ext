# Guide

#### When to use adepthub-ext (AdeptHub extensions), adept-core or adept-lockfile
The Adept(Hub) project is split into 3 parts:
- The **adept-lockfile** project. This is a pure Java project capable only of 1) reading adept lockfiles (see the Lockfile section below for more info) and 2) downloading artifacts. This means it cannot resolve on its own. It is useful for bootstrapping something which has already been resolved, and because it is only is about 50kbs (and 2 jars) and therefore lightweight. Soon AdeptHub will provide resolution as a service, which means that the adept-lockfile project can be enough for online only resolution.
- The **adept-core** project contains the resolution engine and what you need to handle/read Adept metadata. With Adept you can search locally, resolve and write lockfiles. What you do not get is intergration with AdeptHub or Ivy. Also Adept does not know about "versions" or "binary-versions" and so on, because it is unecessary to perform resolution. Adept also does not take any assumptions on how results should be displayed for an end user. This means that you will have to write this on you own or use the adepthub-ext project that has helpers for this.
- This project (the **adepthub-ext** project) contains everything above and also provides the possibility to search, install and publish on adepthub.com. In addition it makes it possible to import from Ivy. Adepthub extensions contains the notion of module. This is useful for tools that needs to support configurations. Further more it uses provides helpers for common notions such as version and binary-versions, which Adept does not need to resolve but which are commonly used. Lastely it contains helpers to make it easier to render end-user results.

The preference should go toward adepthub-ext, because it is the total package. 
This guide explains how to use the adepthub-ext programatically.


### Resolving with AdeptHub extensions

#### Step 1: Instantiate AdeptHub
```scala
import adept.{Defaults, AdeptHub}
import java.io.File
val cacheManager = net.sf.ehcache.CacheManager.create() //cachemanager is kept running to speed up later resolutions
val adepthub = new AdeptHub(baseDir = Defaults.baseDir, importsDir = new File("adept-imports"), cacheManager = cacheManager)
//do stuff...
cacheManager.shutdown() //must shutdown to join cachemanager threads
```

Parameters:
- `baseDir` aka the base directory of Adepthub. Default is: `~/.adept`.
- `importsDir` the directory where imports (unversioned metadata) goes.


#### Step 2: Finding existing modules
It is nice for users to be able search for exisiting modules when they do not know the exact name for a module/variant and also to discover new modules or versions/variants of them. The `search` method does exactly this.
Use the `term` parameter which finds variant ids that contains the `term`. Variant ids usually contains the group and artifact ids for Ivy/Maven of the module and looks like this: "com.typesafe.akka/akka-actor". This means that "akka", "akka-actor" etc will return variants with this Id if present either locally or on AdeptHub.

Searches are executed in parallell on adepthub.comand locally, and will fail if adepthub.com cannot be reached unless the `allowLocalOnly` parameter is set to false. 

NOTE: For search results with variants that exists both online and locally, only the local results will show up. The reason for this is that it makes it easier to only resolve locally and that it makes it possible to know when to fetch the metadata or not. Read more about that in the Resolving section.

```scala
val term = "play-json"
val searchResults = adepthub.search(term)
println(AdeptHub.renderSearchResults(searchResults, term))
```
Use the `constraints` parameter to constrain your search to specific variants.

When looking for a specific variant Id, always append a '/' (`Id.Sep`) to the term, like in the example below:
```scala
import adept.ext.AttributeDefaults
val term = "com.typesafe.play/play-json" + Id.Sep //<- hit only exactly this module
val searchResults = adepthub.search(term, constraints = Set(Constraint(AttributeDefaults.VersionAttribute, Set("2.2.1"))))
println(AdeptHub.renderSearchResults(searchResults, term))
```

#### Step 3: Resolving 
Since Adept requires a context to resolve, the most practical way is to first search (see step 2) for the variants you want, then create requirements and context based on this.
The context and requirements will have to be merged with the ones from the lockfile.
This example shows the whole procedure end-to-end:

```scala
//INPUT START
val term = "com.typesafe.play/play-json" + Id.Sep //<-- adding the / to make sure we get the right module
val constraints = Set(Constraint(AttributeDefaults.VersionAttribute, Set("2.2.0"))) //<-- constrain to only 2.2.0
val lockfileFile = new File("compile.adept") //<-- lockfile file (could be anything, but often we map the name to the conf of OUR module)
val confs = Set("compile", "master") //<-- desired confs of our future requirements (compile has the dependencies, master has the artifacts)
//INPUT END

val highestSearchResultsOnly = {
  val allSearchResults = adepthub.search( //<-- search for all matching term
    term,
    constraints,
    allowLocalOnly = true) //<-- change to false to force online searches - when it is true, Adept will be able to resolve if metadata is locally available
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
  .fold(errorMsg => Failure(new Exception(errorMsg)), res => Success(res)) //<-- convert to Try to use for-expr later (not required/can be implemented differently)
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
    .fold( //<-- convert to Try to use for-expr later (not required/can be implemented differently)
      error => Failure(new Exception(error.toString)), //use AdeptHub.renderErrorReport here to get a nicer error message (still under development)
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
```


#### Step 0/4: Constructing classpaths
This step could be either done right on startup (if a lockfile exists) or after a new lockfile is generated and the artifacts are required.

Below is an example of how to construct a classpath using the lockfile.

```scala
val downloadTimeoutMinutes = 10
val downloadResults = lockfile.download(adepthub.baseDir, downloadTimeoutMinutes, 
    java.util.concurrent.TimeUnit.MINUTES, 5, //<-- max retries 
    //see issue for more info on logging/progress: https://github.com/adept-dm/adept/issues/35
    new adept.logging.TextLogger(adept.logging.TextLogger.INFO), 
    new adept.progress.TextProgressMonitor)
import collection.JavaConverters._
val classpath: String = downloadResults.asScala.map { result =>
  if (result.isSuccess)
    result.getCachedFile
  else throw new Exception("Could not download artifact from: " + result.artifact.locations, result.exception)
}.mkString(":")
```

### Complete example:
The compiled code for these examples can be found here: https://github.com/adepthub/adepthub-ext/blob/master/adepthub-ext/src/main/scala/APIExample.scala


### Library dependencies:
Library dependencies are also available, although, since AdeptHub is in alpha the source is probably the best place to start.

The dependencies can be added like this:
```scala
resolvers += "Typesafe repository" at "http://repo.typesafe.com/typesafe/releases/"

resolvers += Resolver.url("adepthub-sbt-plugin-releases",
  new URL("http://adepthub.github.io/adepthub-ext/releases"))(
    Resolver.ivyStylePatterns)
    
libraryDependencies += "com.adepthub" %% "adepthub-ext" % "0.9.2.6"
```

### Feedback
Feeback is most welcome! We are still early in our process and the code base is flexible so make your voice heard now!

We prefer issues, but mail ('my first name' 'at' 'adepthub' 'dot' 'com') works if there is something you do not wish to share.
