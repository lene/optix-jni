package io.github.lene.optix

import menger.common.Color
import menger.common.Const
import menger.common.ImageSize
import menger.common.Material
import menger.common.Vector
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SpectralDispersionSuite extends AnyFlatSpec with Matchers with RendererFixture:

  private val StdSize = ImageSize(320, 240)

  private def dispersiveDiamond: Material =
    Material.Diamond.copy(dispersion = 33f)

  private def nonDispersiveDiamond: Material =
    Material.Diamond  // dispersion=0 by default

  private def setupScene(material: Material): Unit =
    renderer.clearAllInstances()
    renderer.setCamera(
      Vector[3](0f, 1.5f, 5f),
      Vector[3](0f, 0f, 0f),
      Vector[3](0f, 1f, 0f),
      Const.defaultFovDegrees
    )
    renderer.setLight(Vector[3](1f, 0f, 0.2f), 3.0f)
    renderer.addSphereInstance(Vector[3](0f, 0f, 0f), material)
    // Ground plane for context
    renderer.addPlaneInstance(
      Vector[3](0f, -1f, 0f), -2f,
      Material.matte(Color(1f, 1f, 1f, 1f))
    )

  "Spectral dispersion" should "track spectralRays when cauchy_b > 0" in:
    setupScene(dispersiveDiamond)
    val result = renderer.renderWithStats(StdSize).get
    result.spectralRays should be > 0L
    // All refracted rays should be spectral for a fully dispersive diamond
    result.refractedRays should be > 0L

  it should "have zero spectralRays when dispersion is disabled" in:
    setupScene(nonDispersiveDiamond)
    val result = renderer.renderWithStats(StdSize).get
    result.spectralRays shouldBe 0L

  it should "produce visibly different output with and without dispersion" in:
    // Render with dispersion
    renderer.clearAllInstances()
    setupScene(dispersiveDiamond)
    val dispImage = renderer.render(StdSize)

    // Render without dispersion
    renderer.clearAllInstances()
    setupScene(nonDispersiveDiamond)
    val noDispImage = renderer.render(StdSize)

    // Images should differ — dispersion produces color fringing
    dispImage should not equal noDispImage

  "Cauchy coefficients" should "return (ior, 0) for zero Abbe number" in:
    val (a, b) = Material.cauchyCoefficients(1.5f, 0.0f)
    a shouldBe 1.5f
    b shouldBe 0.0f

  it should "return non-zero B for positive Abbe number" in:
    val (a, b) = Material.cauchyCoefficients(1.5f, 59f)
    a should be < 1.5f      // A < n_d for normal dispersion
    b should be > 0.0f        // B > 0 means refractive index decreases with λ

  "Dispersive presets" should "have correct dispersion values" in:
    Material.GlassDispersive.dispersion shouldBe 59f
    Material.DiamondDispersive.dispersion shouldBe 33f

  it should "differ from non-dispersive presets only in dispersion field" in:
    Material.GlassDispersive.ior shouldBe Material.Glass.ior
    Material.GlassDispersive.color shouldBe Material.Glass.color
    Material.DiamondDispersive.ior shouldBe Material.Diamond.ior
    Material.DiamondDispersive.color shouldBe Material.Diamond.color
