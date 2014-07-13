val scalaVer = "2.11.1"

scalaVersion := scalaVer

scalacOptions += "-feature"

scalacOptions += "-deprecation"

val jvmTarget = "1.6"

incOptions := incOptions.value.withNameHashing(nameHashing = true)

lazy val adeptVersion = "0.9.2.6"

lazy val adepthubOrg = "com.adepthub"

lazy val adeptCore = ProjectRef(file("adept"), "adeptCore") % "test->test;compile->compile"

lazy val adepthubExt = project.in(file("adepthub-ext")).settings( 
  name := "adepthub-ext",
  version := adeptVersion,
  scalaVersion := scalaVer,
  organization := adepthubOrg,
  scalacOptions += "-target:jvm-"+jvmTarget,
  resolvers += Resolver.url("Typesafe Ivy releases", url("https://repo.typesafe.com/typesafe/releases/"))(
    Resolver.ivyStylePatterns),
  libraryDependencies ++= Seq(
    "org.apache.ivy" % "ivy" % "2.3.0",
    "org.apache.httpcomponents" % "httpclient" % "4.3.3",
    "org.apache.httpcomponents" % "httpmime" % "4.3.3",
    "org.apache.commons" % "commons-io" % "1.3.2",
    //TODO: typesafe ivy release is down :( renable "org.scala-sbt.ivy"  % "ivy" % "2.4.0-sbt-d6fca11d63402c92e4167cdf2da91a660d043392",
    "org.scalatest" %% "scalatest" % "2.0" % "test",
    "org.scala-lang.modules" %% "scala-xml" % "1.0.2"
  )
).dependsOn(adeptCore)

lazy val adepthubUI = project.in(file("adepthub-ui")).settings(
  name := "adepthub-ui",
  version := adeptVersion,
  scalaVersion := scalaVer,
  organization := adepthubOrg,
  scalacOptions += "-target:jvm-"+jvmTarget,
  resolvers += "spray repo" at "http://repo.spray.io", //spray
  resolvers += "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/", //akka
  libraryDependencies ++= Seq(
    "io.spray" % "spray-can" % "1.3.1",
    "com.typesafe.akka" %% "akka-actor" % "2.3.4",
    "com.typesafe.akka" %% "akka-slf4j" % "2.3.4" % "runtime",
    "ch.qos.logback" % "logback-classic" % "1.1.1",
    "org.scala-lang.modules" %% "scala-xml" % "1.0.2"
  )
).dependsOn(adeptCore)

lazy val adepthubSbt = project.in(file("adepthub-sbt")).settings( 
  name := "adepthub-sbt",
  version := adeptVersion,
  scalaVersion := scalaVer,
  organization := adepthubOrg,
  scalacOptions += "-target:jvm-"+jvmTarget,
  sbtPlugin := true,
  libraryDependencies ++= Seq(
    "org.slf4j" % "slf4j-nop" % "1.6.1"
  )
).dependsOn(adepthubExt)
