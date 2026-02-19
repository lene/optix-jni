package menger.optix

import menger.common.Color
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class MaterialPresetSuite extends AnyFlatSpec with Matchers:

  // ========== Preset Value Tests ==========

  "Material.Glass" should "have correct IOR for glass (1.5)" in:
    Material.Glass.ior shouldBe 1.5f

  it should "be transparent (low alpha)" in:
    Material.Glass.color.a should be < 0.1f

  it should "be a smooth dielectric" in:
    Material.Glass.roughness shouldBe 0.0f
    Material.Glass.metallic shouldBe 0.0f

  "Material.Water" should "have correct IOR for water (1.33)" in:
    Material.Water.ior shouldBe 1.33f

  it should "be transparent" in:
    Material.Water.color.a should be < 0.1f

  "Material.Diamond" should "have correct IOR for diamond (2.42)" in:
    Material.Diamond.ior shouldBe 2.42f

  it should "be transparent" in:
    Material.Diamond.color.a should be < 0.1f

  "Material.Chrome" should "be fully metallic" in:
    Material.Chrome.metallic shouldBe 1.0f

  it should "be mirror-like (zero roughness)" in:
    Material.Chrome.roughness shouldBe 0.0f

  it should "be opaque" in:
    Material.Chrome.color.a shouldBe 1.0f

  "Material.Gold" should "be metallic with characteristic color" in:
    Material.Gold.metallic shouldBe 1.0f
    Material.Gold.color.r should be > Material.Gold.color.g
    Material.Gold.color.g should be > Material.Gold.color.b

  "Material.Copper" should "be metallic with reddish color" in:
    Material.Copper.metallic shouldBe 1.0f
    Material.Copper.color.r should be > Material.Copper.color.g
    Material.Copper.color.g should be > Material.Copper.color.b

  // ========== Film and Parchment Preset Tests ==========

  "Material.Film" should "be highly transparent (alpha = 0.2)" in:
    Material.Film.color.a shouldBe 0.2f

  it should "have soap film IOR (1.33)" in:
    Material.Film.ior shouldBe 1.33f

  it should "be smooth (low roughness)" in:
    Material.Film.roughness shouldBe 0.1f

  it should "be non-metallic" in:
    Material.Film.metallic shouldBe 0.0f

  it should "have no default emission" in:
    Material.Film.emission shouldBe 0.0f

  it should "have 500nm film thickness for thin-film interference" in:
    Material.Film.filmThickness shouldBe 500.0f

  "Material.Parchment" should "be semi-transparent (alpha = 0.4)" in:
    Material.Parchment.color.a shouldBe 0.4f

  it should "have no refraction (IOR = 1.0)" in:
    // Parchment is translucent but not refractive (light passes straight through)
    Material.Parchment.ior shouldBe 1.0f

  it should "have moderate roughness for diffuse appearance" in:
    Material.Parchment.roughness shouldBe 0.5f

  it should "have beige/tan color (RGB ~245, 222, 179)" in:
    // Allow small tolerance for float precision
    Material.Parchment.color.r shouldBe (245f/255f) +- 0.01f
    Material.Parchment.color.g shouldBe (222f/255f) +- 0.01f
    Material.Parchment.color.b shouldBe (179f/255f) +- 0.01f

  it should "be non-metallic" in:
    Material.Parchment.metallic shouldBe 0.0f

  it should "have low specular for matte finish" in:
    Material.Parchment.specular shouldBe 0.2f

  it should "have no default emission" in:
    Material.Parchment.emission shouldBe 0.0f

  // ========== Factory Method Tests ==========

  "Material.matte" should "create pure diffuse material" in:
    val mat = Material.matte(Color(1.0f, 0.0f, 0.0f, 1.0f))
    mat.roughness shouldBe 1.0f
    mat.specular shouldBe 0.0f
    mat.metallic shouldBe 0.0f

  "Material.plastic" should "create glossy dielectric" in:
    val mat = Material.plastic(Color(0.0f, 1.0f, 0.0f, 1.0f))
    mat.roughness should be > 0.0f
    mat.roughness should be < 1.0f
    mat.metallic shouldBe 0.0f
    mat.ior shouldBe 1.5f

  "Material.metal" should "create metallic material" in:
    val mat = Material.metal(Color(0.0f, 0.0f, 1.0f, 1.0f))
    mat.metallic shouldBe 1.0f
    mat.color.b shouldBe 1.0f

  "Material.glass" should "create transparent material with given color" in:
    val mat = Material.glass(Color(1.0f, 0.5f, 0.5f, 1.0f))
    mat.color.a should be < 0.1f
    mat.ior shouldBe 1.5f
    mat.roughness shouldBe 0.0f

  // ========== fromName Lookup Tests ==========

  "Material.fromName" should "return Glass for 'glass'" in:
    Material.fromName("glass") shouldBe Some(Material.Glass)

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

  it should "return Film for 'film'" in:
    Material.fromName("film") shouldBe Some(Material.Film)

  it should "return Parchment for 'parchment'" in:
    Material.fromName("parchment") shouldBe Some(Material.Parchment)

  it should "return a metal for 'metal'" in:
    val mat = Material.fromName("metal")
    mat shouldBe defined
    mat.get.metallic shouldBe 1.0f

  it should "return a plastic for 'plastic'" in:
    val mat = Material.fromName("plastic")
    mat shouldBe defined
    mat.get.metallic shouldBe 0.0f
    mat.get.roughness should be > 0.0f
    mat.get.roughness should be < 1.0f

  it should "return a matte for 'matte'" in:
    val mat = Material.fromName("matte")
    mat shouldBe defined
    mat.get.roughness shouldBe 1.0f
    mat.get.specular shouldBe 0.0f

  it should "be case insensitive" in:
    Material.fromName("GLASS") shouldBe Some(Material.Glass)
    Material.fromName("Glass") shouldBe Some(Material.Glass)
    Material.fromName("gLaSs") shouldBe Some(Material.Glass)

  it should "return None for unknown material" in:
    Material.fromName("unobtanium") shouldBe None
    Material.fromName("") shouldBe None
    Material.fromName("adamantium") shouldBe None

  // ========== Preset Names Set Tests ==========

  "Material.presetNames" should "contain all preset names" in:
    Material.presetNames should contain("glass")
    Material.presetNames should contain("water")
    Material.presetNames should contain("diamond")
    Material.presetNames should contain("chrome")
    Material.presetNames should contain("gold")
    Material.presetNames should contain("copper")
    Material.presetNames should contain("film")
    Material.presetNames should contain("parchment")
    Material.presetNames should contain("metal")
    Material.presetNames should contain("plastic")
    Material.presetNames should contain("matte")

  it should "have exactly 11 presets" in:
    Material.presetNames.size shouldBe 11

  // ========== Physical Correctness Validation ==========

  "Dielectric presets" should "have IOR > 1.0" in:
    Material.Glass.ior should be > 1.0f
    Material.Water.ior should be > 1.0f
    Material.Diamond.ior should be > 1.0f

  "Metal presets" should "have IOR = 1.0 (no refraction)" in:
    Material.Chrome.ior shouldBe 1.0f
    Material.Gold.ior shouldBe 1.0f
    Material.Copper.ior shouldBe 1.0f

  "All presets" should "have roughness in valid range [0, 1]" in:
    Material.presetNames.flatMap(Material.fromName).foreach { mat =>
      mat.roughness should be >= 0.0f
      mat.roughness should be <= 1.0f
    }

  they should "have metallic in valid range [0, 1]" in:
    Material.presetNames.flatMap(Material.fromName).foreach { mat =>
      mat.metallic should be >= 0.0f
      mat.metallic should be <= 1.0f
    }

  they should "have specular in valid range [0, 1]" in:
    Material.presetNames.flatMap(Material.fromName).foreach { mat =>
      mat.specular should be >= 0.0f
      mat.specular should be <= 1.0f
    }

  they should "have non-negative film thickness" in:
    Material.presetNames.flatMap(Material.fromName).foreach { mat =>
      mat.filmThickness should be >= 0.0f
    }

  "Non-film presets" should "have zero film thickness" in:
    val nonFilmPresets = List("glass", "water", "diamond", "chrome", "gold", "copper", "parchment", "metal", "plastic", "matte")
    nonFilmPresets.flatMap(Material.fromName).foreach { mat =>
      mat.filmThickness shouldBe 0.0f
    }
