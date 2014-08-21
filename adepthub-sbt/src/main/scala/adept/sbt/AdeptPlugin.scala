package adept.sbt

import adept.AdeptHub
import adept.ext.JavaVersions
import adept.lockfile.{Lockfile, LockfileConverters}
import adept.sbt.commands._
import net.sf.ehcache.CacheManager
import sbt.Keys._
import sbt._

object AdeptPlugin extends Plugin {

  import adept.sbt.AdeptKeys._

  def adeptSettings = defaultConfigDependentSettings(Test) ++ defaultConfigDependentSettings(Compile) ++
    defaultConfigDependentSettings(Runtime) ++ Seq(
    adeptLockfileGetter := { conf: String =>
      baseDirectory.value / "project" / "adept" / (conf + ".adept")
    },
    adepthubUrl := "http://adepthub.com",
    adeptDirectory := Path.userHome / ".adept",
    adeptImportsDirectory := baseDirectory.value / "project" / "adept" / "imports",
    adeptTimeout := 60, //minutes
    adeptLockfiles := {
      val AdeptLockfileFilePattern = """(.*)\.adept""".r
      ((baseDirectory.value / "project" / "adept") ** "*.adept").get.flatMap { file =>
        if (file.isFile) {
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
      val confs = Set("compile", "master") //TODO: <-- fix!
      val baseDir = adeptDirectory.value
      val importsDir = adeptImportsDirectory.value
      val scalaBinaryVersion = sbt.Keys.scalaBinaryVersion.value

      val cacheManager = CacheManager.create()
      //(val baseDir: File, val importsDir: File, val url: String, val scalaBinaryVersion: String,
      // val cacheManager: CacheManager
      val (majorJavaVersion, minorJavaVersion) = JavaVersions.getMajorMinorVersion(this.getClass,
        this.getClass.getClassLoader)
      val adepthub = new AdeptHub(baseDir, importsDir, cacheManager)
      lazy val adeptCommands = Seq(
        InstallCommand.using(scalaBinaryVersion, majorJavaVersion, minorJavaVersion, confs,
          ivyConfigurations.value, adeptLockfileGetter.value, adepthub),
        IvyInstallCommand.using(scalaBinaryVersion, majorJavaVersion, minorJavaVersion, confs,
          ivyConfigurations.value, adeptLockfileGetter.value, adepthub),
        ContributeCommand.using(scalaBinaryVersion, majorJavaVersion, minorJavaVersion, confs,
          ivyConfigurations.value, adeptLockfileGetter.value, adepthub),
        SearchCommand.using(adepthub),
        RmCommand.using(scalaBinaryVersion, majorJavaVersion, minorJavaVersion, adeptLockfileGetter.value,
          adepthub),
        InfoCommand.using(adeptLockfileGetter.value, adepthub))

      def adepthubTokenizer = Space ~> adeptCommands.reduce(_ | _)

      Command("ah")(_ => adepthubTokenizer) { (state, adeptCommand) =>
        adeptCommand.execute(state)
      }
    })

  def defaultConfigDependentSettings(conf: Configuration) = Seq(
    adeptLockfileContent in conf := {
      adeptLockfiles.value.get(conf.name).map { lockfileFile =>
        val lockfile = {
          if (lockfileFile.exists())
            Lockfile.read(lockfileFile)
          else
            LockfileConverters.create(Set.empty, Set.empty, Set.empty)
        }
        lockfile
      }.getOrElse {
        LockfileConverters.create(Set.empty, Set.empty, Set.empty)
      }
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
      import scala.collection.JavaConverters._
      lockfile.download(baseDir, downloadTimeoutMinutes, java.util.concurrent.TimeUnit.MINUTES, 5,
        AdeptDefaults.javaLogger(logger), AdeptDefaults.javaProgress).asScala.map { result =>
        if (result.isSuccess)
          Attributed.blank(result.getCachedFile)
        else {
          throw new Exception("Could not download artifact from: " + result.artifact.locations,
            result.exception)
        }
      }.toSeq
    },
    dependencyClasspath in conf <<= (adeptClasspath in conf, internalDependencyClasspath in conf).map {
      (classpath, internal) =>
      classpath ++ internal
    })
}
