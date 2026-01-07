package menger.optix

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
    mat.baseColorTexture shouldBe None
    mat.normalTexture shouldBe None
    mat.roughnessTexture shouldBe None

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
    val mat = Material(testColor, baseColorTexture = Some(0), normalTexture = Some(1), roughnessTexture = Some(2))
    mat.baseColorTexture shouldBe Some(0)
    mat.normalTexture shouldBe Some(1)
    mat.roughnessTexture shouldBe Some(2)

  "Material.fromName" should "return Glass for 'glass' (lowercase)" in:
    Material.fromName("glass") shouldBe Some(Material.Glass)

  it should "return Glass for 'GLASS' (uppercase)" in:
    Material.fromName("GLASS") shouldBe Some(Material.Glass)

  it should "return Glass for 'GlAsS' (mixed case)" in:
    Material.fromName("GlAsS") shouldBe Some(Material.Glass)

  it should "return Water for 'water'" in:
    Material.fromName("water") shouldBe Some(Material.Water)

  it should "return Diamond for 'diamond'" in:
    Material.fromName("diamond") shouldBe Some(Material.Diamond)

  it should "return Chrome for 'chrome'" in:
    Material.fromName("chrome") shouldBe Some(Material.Chrome)

  it should "return Gold for 'gold'" in:
    Material.fromName("gold") shouldBe Some(Material.Gold)

  it should "return Copper for 'copper'" in:
    Material.fromName("copper") shouldBe Some(Material.Copper)

  it should "return a metal Material for 'metal'" in:
    val result = Material.fromName("metal")
    result shouldBe defined
    result.get.metallic shouldBe 1.0f
    result.get.roughness shouldBe 0.1f

  it should "return a plastic Material for 'plastic'" in:
    val result = Material.fromName("plastic")
    result shouldBe defined
    result.get.ior shouldBe 1.5f
    result.get.roughness shouldBe 0.3f

  it should "return a matte Material for 'matte'" in:
    val result = Material.fromName("matte")
    result shouldBe defined
    result.get.roughness shouldBe 1.0f
    result.get.specular shouldBe 0.0f

  it should "return None for empty string" in:
    Material.fromName("") shouldBe None

  it should "return None for unknown material name" in:
    Material.fromName("unknown") shouldBe None

  it should "return None for material name with leading/trailing whitespace" in:
    Material.fromName(" glass ") shouldBe None
    Material.fromName("glass ") shouldBe None
    Material.fromName(" glass") shouldBe None

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

  "Material.presetNames" should "contain all 9 preset names" in:
    Material.presetNames should have size 9

  it should "contain all expected names" in:
    Material.presetNames should contain allOf ("glass", "water", "diamond", "chrome", "gold", "copper", "metal", "plastic", "matte")

  it should "be consistent with fromName (all names should resolve)" in:
    Material.presetNames.foreach { name =>
      Material.fromName(name) shouldBe defined
    }
