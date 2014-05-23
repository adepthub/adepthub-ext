package adept.ext.models

import adept.resolution.models.Variant
import adept.ext.AttributeDefaults
import adept.repository.models.VariantHash
import adept.repository.metadata.VariantMetadata
import adept.hash.Hasher
import adept.resolution.models.Attribute
import adept.resolution.models.Id
import adept.resolution.models.Requirement
import adept.resolution.models.Constraint

case class ModuleHash(value: String) extends AnyVal

object Module {
  def getModules(variants: Set[Variant]) = {
    variants.groupBy(_.attribute(AttributeDefaults.MatureAttribute)).map {
      case (moduleAttribute, variants) =>
        val base = variants.map(_.id.value).reduce(_ intersect _)
        (base, moduleAttribute) -> variants
    }
  }

  def modularise(baseId: Id, variants: Set[Variant]): Map[VariantHash, Variant] = {
    val hashes = variants.map { variant =>
      Hasher.hash(VariantMetadata.fromVariant(variant).hash.value.getBytes ++ variant.id.value.getBytes)
    }
    val moduleHash = Hasher.hash(hashes.mkString(",").getBytes)

    val (baseVariants, otherVariants) = variants.partition(_.id == baseId)

    if (baseVariants.size != 1) throw new Exception("Could not find one base variant with id: " + baseId + " in:\n" + variants.mkString("\n"))

    val attributedBaseVariant = {
      val variant = baseVariants.head
      VariantMetadata.fromVariant(variant).hash -> variant.copy(
        attributes = variant.attributes.filter(_.name != AttributeDefaults.ModuleHashAttribute) +
          Attribute(AttributeDefaults.ModuleHashAttribute, Set(moduleHash)))
    }
    val attributedOtherVariants = otherVariants.map { variant =>
      VariantMetadata.fromVariant(variant).hash ->
        variant.copy(
          attributes = variant.attributes.filter(_.name != AttributeDefaults.ModuleHashAttribute) +
            Attribute(AttributeDefaults.ModuleHashAttribute, Set(moduleHash)),
          requirements = variant.requirements + Requirement(baseId, Set(Constraint(AttributeDefaults.ModuleHashAttribute, Set(moduleHash))), Set.empty))
    }.toMap
    val result = (attributedOtherVariants + attributedBaseVariant)
    if (result.values.size != variants.size) throw new Exception("Could not modularise! Multiple variants with same hashes? Variants are: " + variants + ". Hashes are: " + result.keys.toSet)
    result
  }

  def getModuleHash(variant: Variant): ModuleHash = {
    val values = variant.attribute(AttributeDefaults.ModuleHashAttribute).values
    if (values.size == 1) ModuleHash(values.head)
    else throw new Exception("Could not get module hash from: " + variant)
  }
}