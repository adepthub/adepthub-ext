# AdeptHub Extensions

## Intro
This project extends Adept by providing the possibility to search, install and publish on adepthub.com. In addition it makes it possible to import from Ivy. AdeptHub extensions also contains the notion of module. This is useful for tools that needs to support configurations. Further more it uses provides helpers for common notions such as version and binary-versions, which Adept does not need to resolve but which are commonly used. Lastly it contains helpers to make it easier to render end-user results.

## Install Instructions

NOTE: Adept and the AdeptHub sbt plugin are in alpha - you have been warned!
  - To your `build.sbt` add the line: ```adept.sbt.AdeptPlugin.adeptSettings```
  - To your `project/plugins.sbt` add:

```scala
resolvers += "Typesafe repository" at "http://repo.typesafe.com/typesafe/releases/"

resolvers += Resolver.url("adepthub-sbt-plugin-releases",
  new URL("http://adepthub.github.io/adepthub-ext/releases"))(
    Resolver.ivyStylePatterns)

addSbtPlugin("com.adepthub" % "adepthub-sbt" % "0.9.2.6")
```

 - Now you should be ready to rumble:
    - In sbt try: `ah install` to install a new library. It actually searches for unique modules, so if
     you do `ah install akka-actor/` and there is only one module matching it (which is the case where it will
     work). If you want a particular version use `-v`: `ah install akka-actor/ -v 2.2.1`
    - You can also do: `ah ivy-install` if you are using a library which is not available locally or on
     AdeptHub. When you are done with this do: `ah contribute-imports` to push them to AdeptHub to be more
     reliable, faster and to avoid having a project/adept/imports repository.

## Features

### Reliable

Why is reliability is important for a dependency manager and what do we mean by "reliable"? In our context
"reliable" means that we get the **exact** same result, every... time... One might think this would be the
case for all dependency manager but alas it is not. For Ivy and Maven you are completely dependent on the
repository not doing something weird, such as hosting snapshots for example. You do not want a dependency
manager that is _almost_ reliable (in fact I would say it is worse than having one that is not at all
reliable). Reliable is important, because it makes it easier to reason about what happens, but also
because it makes it easier to build tooling around it. What makes Adept reliable?

  - It only does resolution when something is changed (actively) and something which is resolved is always
   the same.

  - Resolution uses versioned metadata and always points to a specific commit when resolution happened.
   Therefore it operates on exactly the SAME content EVERY time. This also means you can easily go back
   in time.

### High performance

Enhanced performance does not only directly improve developer experience, but can enable build tools to have
features that indirectly makes them more pleasant to work with. What makes Adept perform well?

  - Resolution is faster than Ivy/Maven because there are no roundtrips fetching data. This is faster
   because network IO roundtrips are almost always slower than a batched approach.
  - Resolution does not need to be done very often. The reason is because we have something called a
   "lock file" where all the inputs for resolution (requirements, ...) and artifacts (hashes and locations
   of the files that required) are stored. This lock file is only generated when inputs **changes**.
  - Artifacts have unique hashes and are only EVER downloaded when a hash is not in cache - the cache never
   has to be “invalidated” by Adept
  - Artifacts & repositories are downloaded in parallel and can have multiple locations (downloading from
    the fastest server)
  - Artifact and metadata fetching is separated so it is possible to add new artifact download protocols
   easily (e.g. torrent)

### Distributed

How is Adept distributed? It is distributed in the sense that metadata can be stored anywhere (any server,
locally) and use other metadata from anywhere (any server, locally). That means that, in Adept, we accept that
there is metadata everywhere and handle it. The advantages are:

  - Easy to publish to and manage your own repository
  - Works even if you are on the move because it is offline. It is also possible to do offline resolution
   if the metadata has been downloaded.
  - Increased flexibility because advanced APIs can easily be built on Adept since metadata is offline:
   version ranges, ...
  - Git-like pull requests so anybody can easily improve metadata and contribute. This makes it possible
   to change metadata if it is wrong, so that everybody can benefit from it.
  - Easy to do local modifications to metadata to support a specific use case.
  - Artifacts and repositories can have multiple locations so they are more fault tolerant

### Knows What Works with What

Adept can know what works with what because it uses structured variants instead of "versions" or "semantic
versions", i.e. versions that _mean_ something (version 1.0.2 is higher than and compatible with version
1.0.2):

  - No ambiguities: either _one_ module is chosen or _none_. The author of the module states which
   constraints a user has to specify (must specify a binary-version or even something more exotic the:
   "verified-by-somebody" constraint). The author is also responsible for what the "best" "versions" are.
   This is perfect for libraries, platforms and frameworks which have their own ecosystems with more or less
   specific requirements.
  - The resolution engine compares multiple attributes and constraints to each other with simple equality,
   instead of “smart” and complicated algorithms to figure out which version is “best”. This makes the
   resolution results simple to understand.
  - The resolution engine is simple and written in approx. 200 lines of code. This means less bugs but
    also makes it easy to port to other languages.
  - The resolution engine will search for the _one_ way of combining the modules that resolves the graph
    and for which all variants (e.g. the different "versions") are compatible. For example: if you depend
    on a module, "play-slick", which integrates "play" with "slick", Adept will automatically figure
    out which "play-slick" variant/"version" you need if "play" and "slick" are specified. This makes
    Adept easier to use.
  - Possible to faithfully emulate most (if not all) features of other package managers and any
   "version-scheme", like "semantic versioning" (Scala 2.10 is compatible with 2.10 but not with
   2.11), backwards compatible versioning (Java 1.7.0_51 is compatible with all 1.7.x, 1.6.x, 1.5.x, ...), …
  - Safe and strict, but also lots of possibilities without having to rewrite or add features to the
   resolution engine. Features that can/have been added includes conflicts, safe renames, ivy
   configurations/maven scopes, ...

## AdeptHub

AdeptHub makes Adept better by:

  - Making it easier to discover new modules
  - Making publishing easy, safe and secure
  - Giving the possibility of online resolution making it even faster to resolve and to import
  - Providing and enforcing a set of best-practices: binary version, version, mature attributes, ...
  - Making it easy for users to upgrade to the latest and greatest that is compatible
  - Enabling command line and/or email notifications (security fix, ...) when it is possible to upgrade
  - Providing advanced tooling: online and local UIs, command line interfaces, ....
  - Make it possible to use Ivy and Maven to resolve Adept metadata.

## FAQ

- Are Adept and AdeptHub trying to become the _new standard_?
 - We do not care so much about standards as we care about improving the status quo. The way we see it,
  we simply want to improve the quality of the metadata so it reflects reality (knowing what works with
  what and how things are compatible) at any given point in time (which means it must be easy to change
  safely update the metadata). While we were at it, we saw a chance to improve upon other pain-points of
  some dependency managers when it came to other areas such as reliability and speed. Yes, Adept is
  something different than Ivy/Maven/... but it was hard for us to solve the problems we wanted to fix
  while staying compatible with them.
- Have you guys read the xkcd strip about standardization?
 - Hehe - yeah, that is a good one! We are all big fans of xkcd at AdeptHub and have been reading it
   daily for years! Refer to our Q&A on standards for more information.
- I like Maven/Ant/Ivy/(other dependency/package manager) - are you saying you want us to replace it?
 - Nope, if you really are happy with it, chances are you should continue using it. We are constantly
  thinking of how Adept can help any given build tool or package manager to be better though. We believe
  that Adept is good news for you still though: we have some tricks we are going to do which will use
  the metadata and other features of Adept to enhance your experience even if you don't want to switch
  to Adept! The important thing for us is to get as many library/framework authors/owners to use AdeptHub
  so that metadata can get better.
- Do you know that X dependency/package manager also has Y feature?
 - We have been looking at a lot of dependency/package managers to create a best-of-all-worlds solution.
  We have not looked into all though so it is possible we have not looked at X or know of Y feature. We
  believe we have a really nice solution that solves many problems. We also intend to extend and make
  Adept better with time, and we are open to suggestions and improvements, so if there is something we are
  missing out on please contact us (fredrik 'at' adepthub.com).
- Will AdeptHub support NPM/Bower/CocoaPods/Nuget/Go/Rust/....?
 - We hope so! Our strategy is to make things work on for the Scala and Java ecosystems first, then we will
  continue to other platforms. Adept does not have any specifics for the Scala and Java ecosystems though so
  we believe it should be relatively easy to support other ecosystems as well, but even so, it is likely
  to take time if we want it done properly so help is welcome!
- What is your license?
 - Adept is under the Apache 2.0 license. For anything all source code related to AdeptHub projects:
  you may only use the code under certain conditions. You may use AdeptHub source code for any
  non-commercial and non-competing project, but not for any situation where money is somehow generated
  or where you will be competing with AdeptHub in any way. Send us a mail (fredrik 'at' adepthub.com)
  describing your case if you are uncertain. For AdeptHub server, you will need to download a license file.
  There are licenses for trials, as well as a non-commercial (schools and some not-for-profit usage) and
  a commercial license (for everybody else). The reasons are obvious, but if they are not: hosting costs
  money and we want to make a living out of this. Some of us have families that rely on us as an income
  source and we hope that the value we provide is something people will appreciate enough to want the rights
  to use our software and services in exchange for their money. Our long term plan is to give money back to
  the ecosystems though, but we will get back to the specifics later.
- Can I fix an issue or make a  contribution to AdeptHub projects?
 - Of course! Contributions are welcome and we will make sure your name is listed in our credits. For Adept
  source code, the Apache 2.0 license applies so you are welcome to use it for any project. For AdeptHub
  source code, note that AdeptHub license will cover your changes as well though.  AdeptHub is founded by
  the people who wrote the first Adept implementation and that AdeptHub is the steward of Adept, so any
  donations to AdeptHub (or Adept) will be appreciated as they will be used to build Adept.
- I want to build something better than AdeptHub on Adept, are there any limitations?
 - As long as you do not use AdeptHub source code to do this, we cannot stop you. If you are motivated
  about working within this domain though, perhaps it is easier to join us? Send us a mail (fredrik 'at'
  adepthub.com) - our company is owned by the developers so joining us will give you a part of the cake.
- Don't you agree that the metadata Adept is using is too complex?
 - We can agree that the metadata in Adept is quite complex, but not that it is _too_ complex. There is
  a reason for it being complex you see, and it's simply because Adept is quite stupid in the sense that it
  does not make ANY assumptions. Now the question is whether that is a good thing or not. Our argument for
  it being a good thing is that it makes it possible for Adept to support more use cases in a safe and
  efficient manner, simply because there is no assumptions that can be broken. What we hope will happen
  is that there will be tooling built around Adept that will remove the pain. As a comparison: the
  structure of Git is much more complicated than to that of CVS and Git requires more tooling around it.
  However, by focusing on being fast, portable/distributed and reliable, Git has been proven to be something
  which can easily be extended and the tooling we now take for granted is actually (arguably) part of what
  makes Git great.

Copyright 2014 AdeptHub, Fredrik Ekholdt
