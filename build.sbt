scalaVersion := "2.10.3"

scalacOptions += "-feature"

scalacOptions += "-deprecation"

incOptions := incOptions.value.withNameHashing(true)

lazy val adeptVersion = "0.9.2"

lazy val adepthubOrg = "com.adepthub"

lazy val adeptCore = ProjectRef(file("adept"), "adeptCore") % "test->test;compile->compile"

lazy val adepthubExt = project.in(file("adepthub-ext")).settings( 
  name := "adepthub-ext",
  version := adeptVersion,
  organization := adepthubOrg,
  resolvers += Resolver.url("Typesafe Ivy releases", url("https://repo.typesafe.com/typesafe/releases/"))(Resolver.ivyStylePatterns),
  libraryDependencies ++= Seq(
    "org.apache.ivy" % "ivy" % "2.3.0",
    "org.apache.httpcomponents" % "httpclient" % "4.3.3",
    "org.apache.httpcomponents" % "httpmime" % "4.3.3",
    //TODO: typesafe ivy release is down :( renable "org.scala-sbt.ivy"  % "ivy" % "2.4.0-sbt-d6fca11d63402c92e4167cdf2da91a660d043392",
    "org.scalatest" %% "scalatest" % "2.0" % "test")
).dependsOn(adeptCore)

lazy val adepthubUI = project.in(file("adepthub-ui")).settings(
  name := "adepthub-ui",
  version := adeptVersion,
  organization := adepthubOrg,
  resolvers += "spray repo" at "http://repo.spray.io", //spray
  resolvers += "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/", //akka
  libraryDependencies ++= Seq(
    "io.spray" % "spray-can" % "1.3.1",
    "com.typesafe.akka" %% "akka-actor" % "2.3.0",
    "com.typesafe.akka" %% "akka-slf4j" % "2.3.0" % "runtime",
    "ch.qos.logback" % "logback-classic" % "1.1.1"
  )
).dependsOn(adeptCore)

lazy val adepthubSbt = project.in(file("adepthub-sbt")).settings( 
  name := "adepthub-sbt",
  version := adeptVersion,
  organization := adepthubOrg,
  sbtPlugin := true
).dependsOn(adepthubExt)
