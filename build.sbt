scalaVersion := "2.10.3"

scalacOptions += "-feature"

scalacOptions += "-deprecation"

lazy val adepthubExt = project.in(file("adepthub-ext")).settings( 
  name := "adepthub-ext",
  organization := "com.adepthub",
  version := "0.9.1-SNAPSHOT",
  libraryDependencies ++= Seq(
    "org.scala-sbt.ivy"  % "ivy" % "2.4.0-sbt-d6fca11d63402c92e4167cdf2da91a660d043392",
    "org.scalatest" %% "scalatest" % "2.0" % "test")
).dependsOn(ProjectRef(file("adept"), "adeptCore") % "test->test;compile->compile")

incOptions := incOptions.value.withNameHashing(true)
