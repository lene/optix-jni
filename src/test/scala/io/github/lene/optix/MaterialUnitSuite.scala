package io.github.lene.optix

import scala.jdk.CollectionConverters._
import scala.jdk.OptionConverters._

import menger.common.Color
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers


class MaterialUnitSuite extends AnyFlatSpec with Matchers:

  private val testColor = Color(0.5f, 0.5f, 0.5f, 1.0f)

  "Material case class" should "accept default parameter values" in:
    val mat = Material(testColor)
    mat.ior shouldBe 1.0f
    mat.roughness shouldBe 0.5f
    mat.metallic shouldBe 0.0f
    mat.specular shouldBe 0.5f
    mat.baseColorTexture shouldBe -1
    mat.normalTexture shouldBe -1
    mat.roughnessTexture shouldBe -1

  it should "accept custom ior value" in:
    val mat = Material(testColor, ior = 2.42f)
    mat.ior shouldBe 2.42f

  it should "accept ior value of 0.0 (no validation)" in:
    val mat = Material(testColor, ior = 0.0f)
    mat.ior shouldBe 0.0f

  it should "accept negative ior value (no validation)" in:
    val mat = Material(testColor, ior = -1.0f)
    mat.ior shouldBe -1.0f

  it should "accept ior value at Float.MaxValue" in:
    val mat = Material(testColor, ior = Float.MaxValue)
    mat.ior shouldBe Float.MaxValue

  it should "accept roughness value at boundary 0.0" in:
    val mat = Material(testColor, roughness = 0.0f)
    mat.roughness shouldBe 0.0f

  it should "accept roughness value at boundary 1.0" in:
    val mat = Material(testColor, roughness = 1.0f)
    mat.roughness shouldBe 1.0f

  it should "accept roughness value outside [0,1] (no validation)" in:
    val matNeg = Material(testColor, roughness = -0.5f)
    matNeg.roughness shouldBe -0.5f
    val matHigh = Material(testColor, roughness = 2.0f)
    matHigh.roughness shouldBe 2.0f

  it should "accept metallic value at boundary 0.0" in:
    val mat = Material(testColor, metallic = 0.0f)
    mat.metallic shouldBe 0.0f

  it should "accept metallic value at boundary 1.0" in:
    val mat = Material(testColor, metallic = 1.0f)
    mat.metallic shouldBe 1.0f

  it should "accept metallic value outside [0,1] (no validation)" in:
    val matNeg = Material(testColor, metallic = -0.5f)
    matNeg.metallic shouldBe -0.5f
    val matHigh = Material(testColor, metallic = 1.5f)
    matHigh.metallic shouldBe 1.5f

  it should "accept texture indices" in:
    val mat = Material(testColor, baseColorTexture = 0, normalTexture = 1, roughnessTexture = 2)
    mat.baseColorTexture shouldBe 0
    mat.normalTexture shouldBe 1
    mat.roughnessTexture shouldBe 2

  "Material.fromName" should "return Glass for 'glass' (lowercase)" in:
    Material.fromName("glass").toScala shouldBe Some(Material.Glass)

  it should "return Glass for 'GLASS' (uppercase)" in:
    Material.fromName("GLASS").toScala shouldBe Some(Material.Glass)

  it should "return Glass for 'GlAsS' (mixed case)" in:
    Material.fromName("GlAsS").toScala shouldBe Some(Material.Glass)

  it should "return Water for 'water'" in:
    Material.fromName("water").toScala shouldBe Some(Material.Water)

  it should "return Diamond for 'diamond'" in:
    Material.fromName("diamond").toScala shouldBe Some(Material.Diamond)

  it should "return Chrome for 'chrome'" in:
    Material.fromName("chrome").toScala shouldBe Some(Material.Chrome)

  it should "return Gold for 'gold'" in:
    Material.fromName("gold").toScala shouldBe Some(Material.Gold)

  it should "return Copper for 'copper'" in:
    Material.fromName("copper").toScala shouldBe Some(Material.Copper)

  it should "return a metal Material for 'metal'" in:
    val result = Material.fromName("metal")
    result.isPresent shouldBe true
    result.get.metallic shouldBe 1.0f
    result.get.roughness shouldBe 0.1f

  it should "return a plastic Material for 'plastic'" in:
    val result = Material.fromName("plastic")
    result.isPresent shouldBe true
    result.get.ior shouldBe 1.5f
    result.get.roughness shouldBe 0.3f

  it should "return a matte Material for 'matte'" in:
    val result = Material.fromName("matte")
    result.isPresent shouldBe true
    result.get.roughness shouldBe 1.0f
    result.get.specular shouldBe 0.0f

  it should "return empty Optional for empty string" in:
    Material.fromName("").isPresent shouldBe false

  it should "return empty Optional for unknown material name" in:
    Material.fromName("unknown").isPresent shouldBe false

  it should "return empty Optional for material name with leading/trailing whitespace" in:
    Material.fromName(" glass ").isPresent shouldBe false
    Material.fromName("glass ").isPresent shouldBe false
    Material.fromName(" glass").isPresent shouldBe false

  "Material.matte" should "create material with correct defaults" in:
    val mat = Material.matte(testColor)
    mat.color shouldBe testColor
    mat.ior shouldBe 1.0f
    mat.roughness shouldBe 1.0f
    mat.metallic shouldBe 0.0f
    mat.specular shouldBe 0.0f

  "Material.plastic" should "create material with correct defaults" in:
    val mat = Material.plastic(testColor)
    mat.color shouldBe testColor
    mat.ior shouldBe 1.5f
    mat.roughness shouldBe 0.3f
    mat.metallic shouldBe 0.0f
    mat.specular shouldBe 0.5f

  "Material.metal" should "create material with correct defaults" in:
    val mat = Material.metal(testColor)
    mat.color shouldBe testColor
    mat.ior shouldBe 1.0f
    mat.roughness shouldBe 0.1f
    mat.metallic shouldBe 1.0f
    mat.specular shouldBe 1.0f

  "Material.glass" should "create material with alpha set to 0.02" in:
    val mat = Material.glass(testColor)
    mat.color.a shouldBe 0.02f
    mat.color.r shouldBe testColor.r
    mat.color.g shouldBe testColor.g
    mat.color.b shouldBe testColor.b

  it should "have correct glass properties" in:
    val mat = Material.glass(testColor)
    mat.ior shouldBe 1.5f
    mat.roughness shouldBe 0.0f
    mat.metallic shouldBe 0.0f
    mat.specular shouldBe 1.0f

  "Material presets" should "have Glass with IOR 1.5 (standard glass)" in:
    Material.Glass.ior shouldBe 1.5f
    Material.Glass.roughness shouldBe 0.0f
    Material.Glass.metallic shouldBe 0.0f

  it should "have Water with IOR 1.33" in:
    Material.Water.ior shouldBe 1.33f

  it should "have Diamond with IOR 2.42" in:
    Material.Diamond.ior shouldBe 2.42f

  it should "have Chrome as a metal" in:
    Material.Chrome.metallic shouldBe 1.0f
    Material.Chrome.ior shouldBe 1.0f

  it should "have Gold as a metal with gold color" in:
    Material.Gold.metallic shouldBe 1.0f
    Material.Gold.color.r shouldBe 1.0f
    Material.Gold.color.g shouldBe 0.84f +- 0.01f

  it should "have Copper as a metal with copper color" in:
    Material.Copper.metallic shouldBe 1.0f
    Material.Copper.color.r shouldBe 0.72f +- 0.01f

  "Material.presetNames" should "contain all 11 preset names" in:
    Material.presetNames.asScala should have size 11

  it should "contain all expected names" in:
    Material.presetNames.asScala should contain allOf ("glass", "water", "diamond", "chrome", "gold", "copper", "film", "parchment", "metal", "plastic", "matte")

  it should "be consistent with fromName (all names should resolve)" in:
    Material.presetNames.asScala.foreach { name =>
      Material.fromName(name).isPresent shouldBe true
    }

  // Tests for Material copy behavior (field updates via copy)

  "Material.copy color" should "update color and preserve other fields" in:
    val mat = Material(testColor, ior = 2.0f, roughness = 0.3f)
    val newColor = Color(0.0f, 1.0f, 0.0f, 1.0f)
    val result = mat.copy(color = newColor)
    result.color shouldBe newColor
    result.ior shouldBe 2.0f
    result.roughness shouldBe 0.3f

  "Material.copy ior" should "update ior" in:
    val mat = Material(testColor, ior = 1.5f)
    mat.copy(ior = 2.42f).ior shouldBe 2.42f

  "Material.copy roughness" should "update roughness" in:
    val mat = Material(testColor)
    mat.copy(roughness = 1.0f).roughness shouldBe 1.0f

  "Material.copy metallic" should "update metallic" in:
    val mat = Material(testColor)
    mat.copy(metallic = 1.0f).metallic shouldBe 1.0f

  "Material.copy specular" should "update specular" in:
    val mat = Material(testColor)
    mat.copy(specular = 1.0f).specular shouldBe 1.0f

  "Material.copy chained" should "apply all updates in one call" in:
    val mat = Material(testColor)
    val newColor = Color(1.0f, 0.0f, 0.0f, 1.0f)
    val result = mat.copy(
      color    = newColor,
      ior      = 2.42f,
      roughness = 0.1f,
      metallic = 1.0f,
      specular = 0.8f
    )
    result.color shouldBe newColor
    result.ior shouldBe 2.42f
    result.roughness shouldBe 0.1f
    result.metallic shouldBe 1.0f
    result.specular shouldBe 0.8f

  it should "preserve unchanged fields" in:
    val mat = Material(testColor, ior = 1.5f, roughness = 0.5f)
    val newColor = Color(0.0f, 0.0f, 1.0f, 1.0f)
    val result = mat.copy(
      color    = newColor,
      roughness = 0.0f
    )
    result.color shouldBe newColor
    result.ior shouldBe 1.5f     // Unchanged
    result.roughness shouldBe 0.0f  // Updated
    result.metallic shouldBe 0.0f   // Original default
    result.specular shouldBe 0.5f   // Original default
