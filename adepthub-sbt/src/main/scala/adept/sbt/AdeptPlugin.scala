package adept.sbt

import sbt._
import sbt.Keys._
import adept.lockfile.Lockfile
import adept.lockfile.InternalLockfileWrapper
import adept.sbt.commands._
import adept.AdeptHub
import net.sf.ehcache.CacheManager
import adept.ext.JavaVersions

object AdeptPlugin extends Plugin {

  import AdeptKeys._

  def adeptSettings = defaultConfigDependentSettings(Test) ++ defaultConfigDependentSettings(Compile) ++ defaultConfigDependentSettings(Runtime) ++ Seq(
     adeptLockfileGetter := { conf: String =>
      baseDirectory.value / "project" / "adept" / (conf + ".adept")
    },
    adepthubUrl := "http://adepthub.com",
    adeptDirectory := Path.userHome / ".adept",
    adeptImportsDirectory := baseDirectory.value / "project" / "adept" / "imports",
    adeptTimeout := 60, //minutes
    adeptLockfiles := {
      val AdeptLockfileFilePattern = """(.*)\.adept""".r
      new java.io.File(baseDirectory.value.getAbsoluteFile(), "project/adept").listFiles().flatMap { file =>

        if (file.isFile()) {
          file.getName match {
            case AdeptLockfileFilePattern(conf) =>
              Some(conf -> file)
          }
        } else {
          None
        }
      }.toMap
    },
    sbt.Keys.commands += {
      import sbt.complete.DefaultParsers._
      import sbt.complete._
      val confs = Set("compile", "master") //TODO: <-- fix!
      val baseDir = adeptDirectory.value
      val importsDir = adeptImportsDirectory.value
      val url = adepthubUrl.value
      val scalaBinaryVersion = sbt.Keys.scalaBinaryVersion.value

      val cacheManager = CacheManager.create()
      //(val baseDir: File, val importsDir: File, val url: String, val scalaBinaryVersion: String, val cacheManager: CacheManager
      val (majorJavaVersion, minorJavaVersion) = JavaVersions.getMajorMinorVersion(this.getClass, this.getClass().getClassLoader())
      val adepthub = new AdeptHub(baseDir, importsDir, url, scalaBinaryVersion, majorJavaVersion, minorJavaVersion, cacheManager)
      lazy val adeptCommands = Seq(
        InstallCommand.using(confs, adeptLockfileGetter.value, adepthub),
        ContributeCommand.using(adepthub),
        IvyInstallCommand.using(confs, adeptLockfileGetter.value, adepthub))

      def adepthubTokenizer = (Space ~> adeptCommands.reduce(_ | _))

      Command("ah")(_ => adepthubTokenizer) { (state, adeptCommand) =>
        adeptCommand.execute(state)
      }
    })

  def defaultConfigDependentSettings(conf: Configuration) = Seq(
    adeptLockfileContent in conf := {
      val lockfileFile = adeptLockfiles.value(conf.name)
      val lockfile = {
        if (lockfileFile.exists())
          Lockfile.read(lockfileFile)
        else
          InternalLockfileWrapper.create(Set.empty, Set.empty, Set.empty)
      }
      lockfile
    },
    adeptClasspath in conf := {
      val logger = Keys.streams.value.log
      val libraryDependencies = Keys.libraryDependencies.value
      if (libraryDependencies.nonEmpty) {
        logger.warn("Ignoring libraryDependencies. They can be removed: " + libraryDependencies.mkString(","))
      }
      val lockfile = (adeptLockfileContent in conf).value
      val downloadTimeoutMinutes = adeptTimeout.value
      val baseDir = adeptDirectory.value
      import collection.JavaConverters._
      lockfile.download(baseDir, downloadTimeoutMinutes, java.util.concurrent.TimeUnit.MINUTES, 5, AdeptDefaults.javaLogger(logger), AdeptDefaults.javaProgress).asScala.map { result =>
        if (result.isSuccess())
          Attributed.blank(result.getCachedFile())
        else {
          throw new Exception("Could not download artifact from: " + result.artifact.locations, result.exception)
        }
      }.toSeq
    },
    dependencyClasspath in conf <<= (adeptClasspath in conf, internalDependencyClasspath in conf).map { (classpath, internal) =>
      classpath ++ internal
    })
}