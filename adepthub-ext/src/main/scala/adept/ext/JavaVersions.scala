package adept.ext

import adept.resolution.models._
import java.util.jar.JarFile
import java.io.File
import java.io.InputStream
import adept.ext.models.Module

object JavaVersions {
  val targetVersionLookup = Map(
    (45 -> 3) -> "1.1",
    (46 -> 0) -> "1.2",
    (47 -> 0) -> "1.3",
    (48 -> 0) -> "1.4",
    (49 -> 0) -> "1.5",
    (50 -> 0) -> "1.6",
    (51 -> 0) -> "1.7",
    (52 -> 0) -> "1.8",
    (53 -> 0) -> "1.9" //TODO: Not sure we want this one?
    )

  val binaryVersionsLookup = Map(
    (45 -> 3) -> Set("1.1"),
    (46 -> 0) -> (Set("1.1") + "1.2"),
    (47 -> 0) -> (Set("1.1") + "1.2" + "1.3"),
    (48 -> 0) -> (Set("1.1") + "1.2" + "1.3" + "1.4"),
    (49 -> 0) -> (Set("1.1") + "1.2" + "1.3" + "1.4" + "1.5"),
    (50 -> 0) -> (Set("1.1") + "1.2" + "1.3" + "1.4" + "1.5" + "1.6"),
    (51 -> 0) -> (Set("1.1") + "1.2" + "1.3" + "1.4" + "1.5" + "1.6" + "1.7"),
    (52 -> 0) -> (Set("1.1") + "1.2" + "1.3" + "1.4" + "1.5" + "1.6" + "1.7" + "1.8"),
    (53 -> 0) -> (Set("1.1") + "1.2" + "1.3" + "1.4" + "1.5" + "1.6" + "1.7" + "1.8" + "1.9") //TODO: Not sure we want this one?
    )

  val jvmSystemId = Id("jvm/config/system")
  val jvmBaseId = Id("jvm")

  def getMajorMinorVersion(className: String, classContainer: String, is: InputStream): (Int, Int) = {
    val data = new java.io.DataInputStream(is)
    try {
      if (0xCAFEBABE != data.readInt()) {
        throw new Exception("Invalid entry (class): " + className + ":" + classContainer)
      } else {
        val minor = data.readUnsignedShort();
        val major = data.readUnsignedShort();
        major -> minor
      }
    } finally {
      data.close()
    }
  }

  def getMajorMinorVersion(file: File): Set[(Int, Int)] = {
    val jarFile = new JarFile(file)
    import collection.JavaConverters._
    jarFile.entries().asScala.find(_.getName.endsWith(".class")).map { entry =>
      val is = jarFile.getInputStream(entry)
      try {
        getMajorMinorVersion(entry.getName(), file.getAbsolutePath(), is)
      } finally {
        is.close()
      }
    }.toSet
  }

  def getMajorMinorVersion(clazz: Class[_], classLoader: ClassLoader = Thread.currentThread().getContextClassLoader()): (Int, Int) = {
    val name = {
      val name = clazz.getName
      name.slice(0, name.size).replace(".", "/") + ".class"
    }
    val is = classLoader.getResourceAsStream(name)
    if (is == null) throw new Exception("Could not get input stream for class: " + name + " (used to determine class version)")
    getMajorMinorVersion(name, classLoader.toString, is)
  }

  def getInfo(major: Int, minor: Int) = {
    val targetVersion = targetVersionLookup.getOrElse((major, minor), throw new Exception("Could not determine java target version with major, minor: " + (major, minor)))
    "JVM versions must be higher than: " + targetVersion
  }
  def getVariants(major: Int, minor: Int) = {
    val binaryVersions = binaryVersionsLookup.getOrElse((major, minor), throw new Exception("Could not determine java target version with major, minor: " + (major, minor)))
    val variants = Set(Variant(jvmBaseId,
      Set(Attribute(AttributeDefaults.BinaryVersionAttribute, binaryVersions)),
      Set.empty), Variant(jvmSystemId,
      Set(
          Attribute(AttributeDefaults.BinaryVersionAttribute, binaryVersions),
          Attribute(ConfigurationHelpers.ConfigurationAttribute, Set("system"))),
      Set.empty))
    Module.modularise(jvmBaseId, variants).values
  }

  def getRequirement(major: Int, minor: Int) = {
    val targetVersion = targetVersionLookup.getOrElse((major, minor), throw new Exception("Could not determine java target version with major, minor: " + (major, minor)))
    Requirement(jvmSystemId, Set(Constraint(AttributeDefaults.BinaryVersionAttribute, Set(targetVersion))), Set.empty)
  }
}