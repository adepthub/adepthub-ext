## Concepts

To be able to resolve Adept needs 2 things: 
- a set of requirements; and 
- the context in which is Adept
resolves

The output of a successful resolution is a lockfile which contains the input requirements and context as well
as the resulting artifacts. This lockfile can be saved and re-used instead of resolving again.

Below you have the most important concepts in Adept explained in further detail.

#### Requirements

*A requirement*: *a thing that is compulsory; a necessary condition*

The requirements tell Adept which libraries your user wants/requires. Requirements in Adept are equivalent to
"dependencies" in Maven/Ivy, but we felt requirement is a better name because Adept uses constraints to
determine whether you need something or not. Constraints do a strict **equality** check to verify that a
variant has an attribute with the same name and the same value.

For example "I require a Scala library that is binary compatible with 2.10", which means I am constraining
Adept to any scala library with a 2.10 binary version. I do not "depend" on it, because:  
1. It might be/is
probably already there (as part of the build tool).  
2. There is no unique "thing" which is "Scala library
binary version 2.10", there are multiple variants/versions of the Scala library with a binary version that all
match my requirement (2.10.0, 2.10.1, ...).  
3. It might not be true that you actually are "depending on it"
since the resolution might override your dependency. In Adept, however, the resolution is strict: failed
requirements make resolution fail and, for example, tell you that you need at Scala library 2.11 because one
or more of your libraries requires 2.11.

For Ivy and Maven "dependencies" makes more sense, because they would typically just accept that the latest
version of a transitive dependency needs something like "Scala library 2.11" and try to put Scala 2.11 on your
classpath or ignore it (in case it is overridden). In the case of Scala this is probably not what you want,
not only because these libraries will be binary incompatible, but because if you use a 2.10 lib, you most
likely use the Scala compiler 2.10 which is not compatible. The bottom line is that these errors are not
something you want your dependency manager to gloss over - luckily you for Adept is here to save the day :)

#### Context

*A context*: the circumstances that form the setting for an event, statement, or idea, and in terms of which
*it can be fully understood*

In Adept, metadata is versioned in Git. The context tells Adept what actual Git commit and even which variant
should be used as input for resolution. This makes Adept reliable because for the same requirements and
context, a resolution result will **always** be the same (given that the resolution engine is the same and
that the metadata is indeed available). Currently the context (i.e. the metadata) must be available locally to
Adept, but adepthub-ext has helpers to help with this.

In Adept we have: `requirements` **X** `context` = `result`

The equivalent of a context in Maven/Ivy is the state a repository/the cache is in at the time you resolve.
The state however is something which is mutable (i.e. it can change) depending on the resolvers you use,
whether Maven/Ivy uses the cache or not, and the metadata which is actually there. For a SNAPSHOT for example
the state of the repository changes on each release. Therefore state is something related to time, which fits
well according to the dictionary:

*State*: *the particular condition that someone or something is in at a specific time*

Comparatively, Ivy/Maven where we will have: `dependencies` **X** `state(t)` = `result(t)`, where t is time,
indicating that the state, and thus, the result is dependent on time.

In Adept, the context metadata can be fetched in different ways, but the easiest way is to fetch it from
AdeptHub is by searching for it. Note that searching uses local repositories whenever they are available. This
makes it possible to resolve reliably (no surprises as when Maven/Ivy invalidates their caches) without being
online and makes it more efficient because it knows when it needs to fetch it or not.

#### Variant

*A Variant: a form or version of something that differs in some respect from other forms of the same thing*

A variant is the "atom" of Adept, in the sense that it is not the smallest unit, but sort of...  It is the
equivalent of 'version' or 'revision' in other dependency managers, but since Adept is multi-dimensional
(meaning it can resolve on multiple attributes) it is possible that there are many variants with the same
"version" attribute (e.g. 2 variants with "version": 1.0.0, but with other differing attributes or
requirements). Therefore, we felt 'version' would be a poor choice and came up with the name 'variant'
instead.

#### Resolution algorithm

Resolution in Adept is the process of getting all the variants that match a set of requirements. Adept uses a
different model for resolution than most (all?) other dependency managers because there is no conflict
management during resolution. Before resolution can happen, the transitive context is loaded where the latest
metadata commit is chosen for each repository and all variants gets ranked, i.e. removing lower ranked
variants.  The algorithm is built as follows:  
- Starting with the input requirements, get all variants with
the required ID with attributes matching the constraints. A variant is considered to be *resolved* if it is
the only variant with this ID, then;  
- Traverse the tree of transitive requirements for every resolved
variant and continue with the children. Resolution stops when all IDs have been either: resolved or found
over-constrained (there are no variants that match all of the current constraints). If Adept is
under-constrained (there is more than one variant per ID), it will try every combination of the
under-constrained variants to see if there exists one and only one that can resolve the graph. This step is
called implicit resolve and can be disabled.

The benefits of Adepts resolution engine is that it is very regular (no exceptions to the rules), yet
flexible. By being regular the resolution engine could be written in 200 LOCs (and can be probably optimized
even further). The code is here:
https://github.com/adept-dm/adept/blob/master/adept-core/src/main/scala/adept/resolution/Resolver.scala

Another benefit is that it maps better to many "corner-cases" seen regularly in dependency management. An
example is circular dependencies, commonly (but not exclusively) seen in bootstrapped libraries. For Ivy/Maven
this quickly becomes hairy: which version should override the other in such a setting? What if something else
overrides the version as well? What came first, which version should be used? In Adept, this issue is
non-existent: either exactly one variant is resolved or it is not. If a transitive, circular requirement adds
constraints, making it over-constrained then the result is over-constrained. If not, we know which variants
remain as candidates and continue.

#### Lockfile

Once resolution has completed, the result can be converted to a lockfile (usually the files that end with:
`.adept`). This lockfile contains the requirements, the context to resolve and the artifacts which are the
output of the resolution. It should/can be checked into VCS so somebody/something (a build server for example)
that compiles/runs/tests, can do so without having to resolve. The requirements and context are there so that
Adept can resolve again if a user adds/changes requirements or updates the context (uses a later version of
the metadata for example). The artifacts are a list of all the library files (jars, zips, ...) and are each
represented by: a hash, an optional filename and locations where the files can be downloaded from.

The lockfile makes it possible to checkout a project from a VCS (like Git) and load/get all artifacts needed
without having to resolve or get the metadata. The artifacts are identified with SHA-256 hash and stored in a
local cache for fast retrieval. Artifacts can have multiple locations, because we know the hash of the file we
want to use, thereby speeding up and making artifact retrieval more fault tolerant. An artifact can also have
a filename, which might be necessary for an IDE to load it (and helps debugability). It is possible that there
is more than one filename for the same hash (although in practice this is unlikely to happen often), in which
case an existing file is copied to the cache with the required filename. Hashes also make it possible to
safely download artifacts from multiple/changing locations.

Combining precalculated lockfiles, artifact hashing and parallel downloads, Adept is both fast and reliable
when loading projects which have already been resolved once, which, after all, is the most common task.

#### Versions

Contrary to many modern dependency/package managers, Adept does not put any special meaning (I want to say
semantic, but it is an overloaded word in this context) to version strings: if you constrain a requirement to
a **specific** attribute you will never get a variant with a different attributes, and if there is a conflict
it will fail. By extension this also means that Adept, on its own merits, does not know anything about
"semantic versioning", e.g. "2.1.1" is higher/better than AND compatible with "2.1.0". However, similar and
more powerful capabilities can be obtained by using the traits of Adept's resolution engine and the binary
version and version attributes (which are just are 2 standardised named attributes). In this context, binary
version is used throughout AdeptHub extensions (not in Adept) to indicate which variants are binary
compatible. Here are 3 examples of the most commonly used version schemes and how they are implemented: - To
emulate "standard" Ivy/Maven versioning, all variants are in the same ranking file, sorted by their version
string. The sorting happens when variants are published, contrary to Ivy/Maven where this happens as part of
the resolution process. - To emulate semantic versioning, all compatible variants have the same
binary-version, ranked according to their version in the same ranking file. When there are more than one
ranking file, users will be under-constrained (there is not only one variant) thus forced to specify the
binary version in order to resolve. - To emulate backwards compatibility, all backwards compatible variants
are in the same ranking, each successive variant has it own binary version and all the ones from the former
(Java 1.6 has binary version values: 1.0, 1.1, 1.2, 1.3, 1.4, 1.5, 1.6). When the context is computed, the
variant with highest binary version will be chosen (because of its higher rank).

#### Ranking

Adept does not have versions so in order to determine whether a variant can be replaced by another it uses
rankings specified by the author of the library. Without such a mechanism, resolution would either be
constantly under-constrained (because the constraints are too relaxed) or always over-constrained (since a
user would have to specify exact versions for each variant). Combining ranking with Adepts resolution engine,
it is possible to create compatibility matrixes mapping to many different versions schemes (which, again, are
specified by the author of the library): semantic versioning, backwards compatible versioning etc. The ranking
file contains an ordered sequence of hashes for variants deemed replaceable. If a variant is lower ranked than
another it will be removed. If the ranking does not have this variant it will always be included.

There can be multiple ranking files containing a series of compatible variants (2.0.x series, 2.1.x series,
etc).

Ranking of variants happens prior to resolution, which makes it easier to debug and predict results because it
is possible to inspect which variants that are chosen.

Combining the ranking model with context and requirements, Adept has a comparatively small amount of LOC
dedicated to resolution logic (about 100 lines for ranking logic, 100 lines for loading metadata and 200 for
the resolution engine with in a standard Scala formatting scheme (and no long one-liners) including comments).
This promotes code stability, reduces bugs and makes it easier to port.

#### Modules & Configurations

*A module: any of a number of distinct but interrelated units from which a program may be built up or into
*which a complex activity may be analysed.*

In AdeptHub, a  module is a set of variants representing all the different configurations of a "release"
mimicking Ivy's modules. Adept itself does not need to know about configurations nor modules to be able to
resolve, which is the reason it only exists in adepthub-ext. A configuration in a module typically maps to a
specific set of requirements and/or artifacts, for example one to be able to test and a different

The way it works is by having one base variant id ("com.typesafe.akka/akka-actor") and a variant per
configuration ("com.typesafe.akka/akka-actor/config/master", "com.typesafe.akka/akka-actor/config/compile", ...).
Each variant in a module has the same, unique attribute called "module-hash". For configurations that "extend"
other configurations, the representing variant simply requires the ID and module hash of the other module. All
configurations require the base ID and its own module-hash. This way you will never get different
configurations of the same module.
