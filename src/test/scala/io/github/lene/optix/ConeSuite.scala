package io.github.lene.optix

import io.github.lene.optix.ColorConstants.OPAQUE_BLUE
import io.github.lene.optix.ColorConstants.OPAQUE_GREEN
import io.github.lene.optix.ColorConstants.OPAQUE_RED
import io.github.lene.optix.ColorConstants.OPAQUE_WHITE
import io.github.lene.optix.ThresholdConstants.QUICK_TEST_SIZE
import io.github.lene.optix.ThresholdConstants.TEST_IMAGE_SIZE
import menger.common.Vector
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers


class ConeSuite extends AnyFlatSpec with Matchers with RendererFixture:

  OptiXRenderer.isLibraryLoaded shouldBe true

  // ========== Basic Cone Creation ==========

  "addConeInstance" should "return a valid instance ID for a simple cone" in:
    val apex = Vector[3](0.0f, 1.0f, 0.0f)
    val base = Vector[3](0.0f, -1.0f, 0.0f)
    val result = renderer.addConeInstance(apex, base, 0.5f, Material.Chrome)
    result.value should be >= 0

  it should "return a valid instance ID with Material parameter" in:
    val apex = Vector[3](0.0f, 0.0f, 1.0f)
    val base = Vector[3](0.0f, 0.0f, -1.0f)
    val result = renderer.addConeInstance(apex, base, 0.3f, Material.Glass)
    result.value should be >= 0

  it should "return a valid instance ID with color and IOR parameters" in:
    val apex = Vector[3](0.0f, 1.0f, 0.0f)
    val base = Vector[3](0.0f, 0.0f, 0.0f)
    val result = renderer.addConeInstance(apex, base, 0.4f, OPAQUE_GREEN, 1.0f)
    result.value should be >= 0

  it should "support various orientations" in:
    // Cone along X axis
    val xCone = renderer.addConeInstance(
      Vector[3](1.0f, 0.0f, 0.0f),
      Vector[3](-1.0f, 0.0f, 0.0f),
      0.4f, Material.Chrome
    )
    xCone.value should be >= 0

    // Cone along Y axis
    val yCone = renderer.addConeInstance(
      Vector[3](0.0f, 1.0f, 0.0f),
      Vector[3](0.0f, -1.0f, 0.0f),
      0.4f, Material.Chrome
    )
    yCone.value should be >= 0

    // Cone along Z axis
    val zCone = renderer.addConeInstance(
      Vector[3](0.0f, 0.0f, 1.0f),
      Vector[3](0.0f, 0.0f, -1.0f),
      0.4f, Material.Chrome
    )
    zCone.value should be >= 0

  // ========== Multiple Cones ==========

  "Multiple cones" should "be addable in sequence" in:
    val ids = for i <- 0 until 3 yield
      val x = (i - 1) * 0.8f
      renderer.addConeInstance(
        Vector[3](x, 0.5f, 0.0f),
        Vector[3](x, -0.5f, 0.0f),
        0.2f, Material.Chrome
      )
    ids.foreach(_.value should be >= 0)
    ids.distinct.size shouldBe 3

  // ========== Cone Rendering ==========

  "Cone rendering" should "produce non-empty image data" in:
    renderer.addConeInstance(
      Vector[3](0.0f, 0.8f, 0.0f),
      Vector[3](0.0f, -0.8f, 0.0f),
      0.4f,
      Material.Chrome
    )
    val imageData = renderImage(QUICK_TEST_SIZE)
    imageData.length shouldBe ImageValidation.imageByteSize(QUICK_TEST_SIZE)

  it should "render visible cone geometry" in:
    renderer.addConeInstance(
      Vector[3](0.0f, 1.0f, 0.0f),
      Vector[3](0.0f, -1.0f, 0.0f),
      0.6f,
      Material(OPAQUE_GREEN, ior = 1.0f)
    )
    val imageData = renderImage(TEST_IMAGE_SIZE)
    val stdDev = ImageValidation.brightnessStdDev(imageData, TEST_IMAGE_SIZE)
    stdDev should be > 5.0

  // ========== Cone Transparency (Sprint 36 H1.4) ==========
  //
  // __closesthit__cone had no alpha test at all: it went straight to the metallic check and
  // then to flat diffuse shading, so material=glass rendered as opaque gray (manual test
  // "Cone glass"). Both cones below share geometry, colour and IOR and differ *only* in
  // alpha, so a shader that ignores alpha produces two identical images.

  private def coneImageWithAlpha(alpha: Float): Array[Byte] =
    renderer.clearAllInstances()
    renderer.addConeInstance(
      Vector[3](0.9f, 0.8f, 0.0f),
      Vector[3](0.9f, -0.8f, 0.0f),
      0.4f,
      Material(menger.common.Color(1.0f, 1.0f, 1.0f, alpha), ior = 1.5f)
    ).value should be >= 0
    renderImage(TEST_IMAGE_SIZE)

  "A transparent cone" should "not render identically to an opaque one" in:
    val transparent = coneImageWithAlpha(0.02f)
    val opaque = coneImageWithAlpha(1.0f)

    val differing = transparent.indices.count(i => transparent(i) != opaque(i))
    withClue(
      s"transparent and opaque cones rendered ${differing} differing bytes of " +
      s"${transparent.length} — alpha is being ignored by __closesthit__cone: "
    ) {
      differing should be > 0
    }

  it should "refract the scene behind it rather than shade flat" in:
    val transparent = coneImageWithAlpha(0.02f)
    val opaque = coneImageWithAlpha(1.0f)

    // A refracting cone bends the floor plane and background through its body, so its own
    // pixels vary more than a flat-lit diffuse cone's do.
    val transparentVariation = ImageValidation.brightnessStdDev(transparent, TEST_IMAGE_SIZE)
    val opaqueVariation = ImageValidation.brightnessStdDev(opaque, TEST_IMAGE_SIZE)
    transparentVariation should not be opaqueVariation

  it should "render multiple colored cones" in:
    renderer.addConeInstance(
      Vector[3](-1.0f, 0.6f, 0.0f),
      Vector[3](-1.0f, -0.6f, 0.0f),
      0.3f, Material(OPAQUE_RED, ior = 1.0f)
    )
    renderer.addConeInstance(
      Vector[3](0.0f, 0.6f, 0.0f),
      Vector[3](0.0f, -0.6f, 0.0f),
      0.3f, Material(OPAQUE_GREEN, ior = 1.0f)
    )
    renderer.addConeInstance(
      Vector[3](1.0f, 0.6f, 0.0f),
      Vector[3](1.0f, -0.6f, 0.0f),
      0.3f, Material(OPAQUE_BLUE, ior = 1.0f)
    )
    val imageData = renderImage(TEST_IMAGE_SIZE)
    val ratio = ImageValidation.colorChannelRatio(imageData, TEST_IMAGE_SIZE)
    ratio.r should be > 0.1
    ratio.g should be > 0.1
    ratio.b should be > 0.1

  // ========== Cone receiving shadows (optix-jni#109) ==========

  // A matte (diffuse-only) cone, lit from straight above, so that its shading is the same all
  // around the axis. A sphere hovers beside the cone over the front flank at azimuth +45 degrees;
  // its shadow falls on that flank, and the flank at azimuth -45 degrees, the mirror image
  // across the x = 0 plane, stays lit. A highlight would not show here: this measures the
  // diffuse darkening of the flank.
  private val ConeApex = Vector[3](0.0f, 1.0f, 0.0f)
  private val ConeBase = Vector[3](0.0f, -1.0f, 0.0f)
  private val ConeBaseRadius = 1.0f
  private val ConeLight = Vector[3](0.0f, -1.0f, 0.0f)
  private val CameraDistance = 3.0f
  private val CameraFov = 60.0f
  // Half-way down the cone (y = 0) its radius is half the base's; 45 degrees towards the camera.
  private val SpotHeight = 0.0f
  private val SpotRadius = ConeBaseRadius * 0.5f
  private val SpotOffset = (SpotRadius * math.sqrt(0.5)).toFloat
  // Over the spot, clear of the flank (it is 0.31 from the sphere's centre) and out of the
  // spot's line of sight; its shadow (radius 0.2) covers the sampled spot (8 px, about 0.1).
  private val OccluderHeight = 0.7f
  private val OccluderRadius = 0.2f
  private val SampleRadiusPixels = 8
  private val MaxShadowedToLitRatio = 0.7
  private val MaxUnshadowedAsymmetry = 0.05
  // How much brighter (0..255 levels) the lit flank must be than with the light off, so that the
  // comparisons never run on near-black pixels.
  private val MinLitGain = 20.0

  /** Brightness of the front flank at world (x, SpotHeight, SpotOffset). The image is mirrored
    * horizontally, so world +x is on its left, as in CylinderSuite. */
  private def flankBrightness(image: Array[Byte], x: Float): Double =
    val depth = CameraDistance - SpotOffset
    val halfWidthWorld = depth * math.tan(math.toRadians(CameraFov / 2.0))
    val halfWidthPixels = TEST_IMAGE_SIZE.width / 2
    val column = halfWidthPixels - (x / halfWidthWorld * halfWidthPixels).toInt
    val row = TEST_IMAGE_SIZE.height / 2 - (SpotHeight / halfWidthWorld * halfWidthPixels).toInt
    TestUtilities.regionBrightness(
      image, TEST_IMAGE_SIZE, TestUtilities.Region.centered(column, row, SampleRadiusPixels)
    )

  /** (shadow side, lit side) of the flank. */
  private def renderConeUnderOccluder(
      shadows: Boolean, intensity: Float = 1.0f
  ): (Double, Double) =
    renderer.clearAllInstances()
    renderer.setShadows(shadows)
    renderer.setCamera(
      Vector[3](0.0f, 0.0f, CameraDistance), Vector[3](0.0f, 0.0f, 0.0f),
      Vector[3](0.0f, 1.0f, 0.0f), CameraFov
    )
    renderer.setLight(ConeLight, intensity)
    renderer.addConeInstance(
      ConeApex, ConeBase, ConeBaseRadius, Material.matte(OPAQUE_WHITE)
    ).value should be >= 0
    renderer.addSphereInstance(
      sphereTransform(SpotOffset, OccluderHeight, SpotOffset, OccluderRadius),
      Material.matte(OPAQUE_WHITE)
    ).value should be >= 0
    val image = renderImage(TEST_IMAGE_SIZE)
    (flankBrightness(image, SpotOffset), flankBrightness(image, -SpotOffset))

  private def litAboveAmbient(litSide: Double): Unit =
    val (_, ambientOnly) = renderConeUnderOccluder(shadows = true, intensity = 0.0f)
    litSide should be > ambientOnly + MinLitGain

  "A cone's flank" should "receive the shadow of a sphere above it" in:
    val (shadowSide, litSide) = renderConeUnderOccluder(shadows = true)
    litAboveAmbient(litSide)
    shadowSide should be < MaxShadowedToLitRatio * litSide

  it should "be evenly lit when shadows are off" in:
    val (shadowSide, litSide) = renderConeUnderOccluder(shadows = false)
    litAboveAmbient(litSide)
    math.abs(shadowSide - litSide) should be < MaxUnshadowedAsymmetry * litSide
