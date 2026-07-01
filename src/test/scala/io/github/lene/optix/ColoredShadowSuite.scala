package io.github.lene.optix

import io.github.lene.optix.ColorConstants.OPAQUE_BLUE
import io.github.lene.optix.ColorConstants.OPAQUE_GREEN
import io.github.lene.optix.ColorConstants.OPAQUE_RED
import io.github.lene.optix.ColorConstants.OPAQUE_WHITE
import io.github.lene.optix.ColorConstants.withAlpha
import io.github.lene.optix.ImageValidation.getRGBAt
import io.github.lene.optix.ShadowValidation.Region
import io.github.lene.optix.ShadowValidation.detectDarkestRegion
import io.github.lene.optix.ShadowValidation.regionBrightness
import io.github.lene.optix.ThresholdConstants.ACHROMATIC_CHANNEL_TOLERANCE
import io.github.lene.optix.ThresholdConstants.DEFAULT_SHADOW_GRID
import io.github.lene.optix.ThresholdConstants.MIN_COLOR_TINT_DIFFERENCE
import io.github.lene.optix.ThresholdConstants.STANDARD_IMAGE_SIZE
import io.github.lene.optix.ThresholdConstants.TRANSPARENT_OPAQUE_BRIGHTNESS_RATIO
import menger.common.Color
import menger.common.Const
import menger.common.ImageSize
import menger.common.Vector
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ColoredShadowSuite
    extends AnyFlatSpec
    with Matchers
    with RendererFixture:

  private val imageSize = STANDARD_IMAGE_SIZE
  private val transparentAlpha = 0.8f

  private def setupColoredShadowScene(
    sphereColor: Color,
    coloredShadows: Boolean = false
  ): Unit =
    renderer.setSphere(Vector[3](0.0f, 0.0f, 0.0f), 0.5f)
    renderer.setSphereColor(sphereColor)
    renderer.setIOR(Const.iorGlass)
    renderer.setScale(1.0f)
    renderer.clearPlanes()
    renderer.addPlaneSolidColor(
      1, true, -0.6f,
      Color.LIGHT_GRAY.r, Color.LIGHT_GRAY.g, Color.LIGHT_GRAY.b
    )
    renderer.setCamera(
      Vector[3](0.0f, 0.0f, 5.0f),
      Vector[3](0.0f, -0.3f, 0.0f),
      Vector[3](0.0f, 1.0f, 0.0f),
      45.0f
    )
    renderer.setLight(Vector[3](0.5f, 0.5f, -0.5f), 1.0f)
    renderer.setShadows(true)
    renderer.setTransparentShadows(coloredShadows)

  private def regionChannelAverages(
    imageData: Array[Byte],
    size: ImageSize,
    region: Region
  ): (Double, Double, Double) =
    val samples = for
      y <- region.y0 until region.y1
        if y >= 0 && y < size.height
      x <- region.x0 until region.x1
        if x >= 0 && x < size.width
    yield getRGBAt(imageData, size, x, y)

    if samples.isEmpty then (0.0, 0.0, 0.0)
    else
      val count = samples.length.toDouble
      (
        samples.map(_.r.toDouble).sum / count,
        samples.map(_.g.toDouble).sum / count,
        samples.map(_.b.toDouble).sum / count
      )

  private def findShadowRegion(image: Array[Byte]): Region =
    detectDarkestRegion(image, imageSize, gridSize = DEFAULT_SHADOW_GRID)

  // ========== Test 1: Default config ==========

  "Colored shadows" should
    "be disabled by default" in:
      setupColoredShadowScene(
        withAlpha(OPAQUE_RED, transparentAlpha),
        coloredShadows = false
      )
      val imageDefault = renderer.render(imageSize)

      setupColoredShadowScene(
        withAlpha(OPAQUE_RED, transparentAlpha),
        coloredShadows = false
      )
      val imageExplicitOff = renderer.render(imageSize)

      withClue("Default config should match explicit off") {
        java.util.Arrays.equals(
          imageDefault, imageExplicitOff
        ) shouldBe true
      }

  // ========== Test 2: No crash ==========

  it should "not crash when enabled" in {
    noException should be thrownBy {
      setupColoredShadowScene(
        withAlpha(OPAQUE_RED, transparentAlpha),
        coloredShadows = true
      )
      val image = renderer.render(imageSize)
      image.length shouldBe
        imageSize.width * imageSize.height * 4
    }
  }

  // ========== Test 3: Red sphere ==========

  it should
    "cast shadow with reduced red attenuation for" +
    " red transparent sphere" in:
      setupColoredShadowScene(
        withAlpha(OPAQUE_RED, transparentAlpha),
        coloredShadows = true
      )
      val image = renderer.render(imageSize)
      val shadowRegion = findShadowRegion(image)
      val (avgR, avgG, avgB) =
        regionChannelAverages(image, imageSize, shadowRegion)

      withClue(
        f"Red sphere shadow: R=$avgR%.1f " +
        f"G=$avgG%.1f B=$avgB%.1f — " +
        "R should be brighter than G and B"
      ) {
        (avgR - avgG) should be > MIN_COLOR_TINT_DIFFERENCE
        (avgR - avgB) should be > MIN_COLOR_TINT_DIFFERENCE
      }

  // ========== Test 4: Blue sphere ==========

  it should "cast blue-tinted shadow for blue" +
    " transparent sphere" in:
      setupColoredShadowScene(
        withAlpha(OPAQUE_BLUE, transparentAlpha),
        coloredShadows = true
      )
      val image = renderer.render(imageSize)
      val shadowRegion = findShadowRegion(image)
      val (avgR, avgG, avgB) =
        regionChannelAverages(image, imageSize, shadowRegion)

      withClue(
        f"Blue sphere shadow: R=$avgR%.1f " +
        f"G=$avgG%.1f B=$avgB%.1f — " +
        "B should be brighter than R and G"
      ) {
        (avgB - avgR) should be > MIN_COLOR_TINT_DIFFERENCE
        (avgB - avgG) should be > MIN_COLOR_TINT_DIFFERENCE
      }

  // ========== Test 5: Green sphere ==========

  it should "cast green-tinted shadow for green" +
    " transparent sphere" in:
      setupColoredShadowScene(
        withAlpha(OPAQUE_GREEN, transparentAlpha),
        coloredShadows = true
      )
      val image = renderer.render(imageSize)
      val shadowRegion = findShadowRegion(image)
      val (avgR, avgG, avgB) =
        regionChannelAverages(image, imageSize, shadowRegion)

      withClue(
        f"Green sphere shadow: R=$avgR%.1f " +
        f"G=$avgG%.1f B=$avgB%.1f — " +
        "G should be brighter than R and B"
      ) {
        (avgG - avgR) should be > MIN_COLOR_TINT_DIFFERENCE
        (avgG - avgB) should be > MIN_COLOR_TINT_DIFFERENCE
      }

  // ========== Test 6: White sphere ==========

  it should "cast lighter shadow for white transparent sphere" +
    " than scalar path (all channels transmit equally)" in:
      // White sphere: colored attenuation = alpha*(1-1, 1-1, 1-1) = (0,0,0)
      // shadow_factor = (1, 1, 1) => no shadow attenuation from shadow ray
      // But the sphere body still affects region via refraction/reflection.
      // Compare with scalar path which gives uniform alpha shadow.
      setupColoredShadowScene(
        withAlpha(OPAQUE_WHITE, transparentAlpha),
        coloredShadows = true
      )
      val coloredImage = renderer.render(imageSize)

      setupColoredShadowScene(
        withAlpha(OPAQUE_WHITE, transparentAlpha),
        coloredShadows = false
      )
      val scalarImage = renderer.render(imageSize)

      // Use scalar image to find shadow (it has real shadow)
      val shadowRegion = findShadowRegion(scalarImage)

      val brightColored =
        regionBrightness(coloredImage, imageSize, shadowRegion)
      val brightScalar =
        regionBrightness(scalarImage, imageSize, shadowRegion)

      withClue(
        "White sphere colored path brightness=" +
        f"$brightColored%.1f should be brighter than " +
        f"scalar path brightness=$brightScalar%.1f " +
        "(colored path has zero shadow attenuation)"
      ) {
        brightColored should be > brightScalar
      }

  // ========== Test 7: Fully opaque ==========

  it should "cast dark achromatic shadow for fully opaque" +
    " gray sphere" in:
      // Gray sphere (equal RGB channels): with colored shadows,
      // attenuation per channel = 1.0*(1 - 0.5) = 0.5 for each,
      // so shadow_factor = (0.5, 0.5, 0.5) — uniform and dark.
      val opaqueGray = Color(0.5f, 0.5f, 0.5f, 1.0f)
      setupColoredShadowScene(opaqueGray, coloredShadows = true)
      val image = renderer.render(imageSize)
      val shadowRegion = findShadowRegion(image)
      val (avgR, avgG, avgB) =
        regionChannelAverages(image, imageSize, shadowRegion)
      val maxSpread = List(
        math.abs(avgR - avgG),
        math.abs(avgR - avgB),
        math.abs(avgG - avgB)
      ).max

      withClue(
        "Opaque gray sphere shadow " +
        f"(R=$avgR%.1f G=$avgG%.1f B=$avgB%.1f) " +
        "should have no color tint (equal channels)"
      ) {
        maxSpread should be < ACHROMATIC_CHANNEL_TOLERANCE
      }

  // ========== Test 8: Fully transparent ==========

  it should "cast no shadow for fully transparent sphere" +
    " regardless of color" in:
      // alpha=0: attenuation = 0*(1-c) = (0,0,0) for any color
      // shadow_factor = (1,1,1) => no shadow attenuation
      setupColoredShadowScene(
        Color(1.0f, 0.0f, 0.0f, 0.0f),
        coloredShadows = true
      )
      val transparentImage = renderer.render(imageSize)

      // Compare with opaque to find shadow region
      setupColoredShadowScene(
        Color(1.0f, 0.0f, 0.0f, 1.0f),
        coloredShadows = true
      )
      val opaqueImage = renderer.render(imageSize)
      val shadowRegion = findShadowRegion(opaqueImage)

      val brightTransparent =
        regionBrightness(transparentImage, imageSize, shadowRegion)
      val brightOpaque =
        regionBrightness(opaqueImage, imageSize, shadowRegion)

      withClue(
        "Transparent sphere shadow region " +
        f"brightness=$brightTransparent%.1f should be " +
        f"much brighter than opaque=$brightOpaque%.1f"
      ) {
        brightTransparent should be > (
          brightOpaque * TRANSPARENT_OPAQUE_BRIGHTNESS_RATIO
        )
      }

  // ========== Test 9: Colored on vs off differ ==========

  it should "produce different images for colored" +
    " transparent sphere with flag on vs off" in:
      val sphereColor = withAlpha(OPAQUE_RED, transparentAlpha)

      setupColoredShadowScene(sphereColor, coloredShadows = false)
      val imageOff = renderer.render(imageSize)

      setupColoredShadowScene(sphereColor, coloredShadows = true)
      val imageOn = renderer.render(imageSize)

      withClue(
        "Colored shadows on vs off should produce " +
        "different images for red transparent sphere"
      ) {
        java.util.Arrays.equals(imageOff, imageOn) shouldBe false
      }

  // ========== Test 10: White sphere on vs off differ ==========

  it should "produce different images for white sphere" +
    " with flag on vs off" in:
      // NOTE: White sphere with colored shadows produces NO shadow
      // (attenuation = alpha*(1-1,1-1,1-1) = (0,0,0)), while scalar
      // path produces uniform shadow (attenuation = alpha). So these
      // images SHOULD differ, contrary to naive expectation.
      val sphereColor = withAlpha(OPAQUE_WHITE, transparentAlpha)

      setupColoredShadowScene(sphereColor, coloredShadows = false)
      val imageOff = renderer.render(imageSize)

      setupColoredShadowScene(sphereColor, coloredShadows = true)
      val imageOn = renderer.render(imageSize)

      withClue(
        "White sphere with colored shadows on vs off should " +
        "differ: scalar path gives uniform shadow, " +
        "colored path gives no shadow"
      ) {
        java.util.Arrays.equals(imageOff, imageOn) shouldBe false
      }

  // ========== Multi-object tests (Phase 2: anyhit accumulation) ==========

  // Sphere transform: 4x3 row-major matrix — uniform scale r and translate (cx, cy, cz)
  private def sphereTransform(cx: Float, cy: Float, cz: Float, r: Float): Array[Float] =
    Array(r, 0f, 0f, cx, 0f, r, 0f, cy, 0f, 0f, r, cz)

  // Two spheres vertically aligned for light-from-above shadow path.
  // Both centered at x=0,z=0 so their shadows overlap on the floor.
  // Shadow rays from the floor travel in +y, passing through low sphere then high.
  // Camera looks downward so both spheres appear in the upper half of the image
  // and the floor shadow appears in the lower half (where detectDarkestRegion scans).
  // Radius 0.2 gives a ~40px shadow circle on a 400px image, covering ~39% of
  // the detected 80×40-pixel grid cell — enough to produce clear channel differences.
  private val lowSphereTransform  = sphereTransform(0f, 0.0f, 0f, 0.2f)
  private val highSphereTransform = sphereTransform(0f, 0.8f, 0f, 0.2f)

  // Set up the static scene elements (camera, light, floor) for multi-sphere tests.
  // Callers add sphere instances separately.
  private def setupMultiSphereSceneBase(transparentShadows: Boolean): Unit =
    renderer.clearPlanes()
    renderer.addPlaneSolidColor(
      1, true, -0.6f,
      Color.LIGHT_GRAY.r, Color.LIGHT_GRAY.g, Color.LIGHT_GRAY.b
    )
    // Same downward-tilted camera as existing colored shadow tests so the floor
    // dominates the lower half of the image and detectDarkestRegion finds the
    // floor shadow rather than the transparent sphere bodies.
    renderer.setCamera(
      Vector[3](0.0f, 0.0f, 5.0f),
      Vector[3](0.0f, -0.3f, 0.0f),
      Vector[3](0.0f, 1.0f, 0.0f),
      45.0f
    )
    // Light straight up so shadow rays travel vertically through both stacked spheres
    renderer.setLight(Vector[3](0.0f, 1.0f, 0.0f), 1.0f)
    renderer.setShadows(true)
    renderer.setTransparentShadows(transparentShadows)

  // ========== Test 11: Combined tint from two transparent spheres ==========

  it should "accumulate attenuation from two transparent spheres (Phase 2)" in:
    val transparentBlue = withAlpha(OPAQUE_BLUE, transparentAlpha)
    val transparentRed  = withAlpha(OPAQUE_RED,  transparentAlpha)

    // One blue sphere: shadow ray hits blue (attenuates R, G; passes B)
    // → B channel NOT attenuated in shadow
    renderer.clearAllInstances()
    renderer.addSphereInstance(lowSphereTransform, transparentBlue, Const.iorGlass)
    setupMultiSphereSceneBase(transparentShadows = true)
    val oneSphereImage = renderer.render(imageSize)
    val shadowRegion   = findShadowRegion(oneSphereImage)
    val (_, _, oneSphereAvgB) =
      regionChannelAverages(oneSphereImage, imageSize, shadowRegion)

    // Two spheres: blue (low) + red (high). Red attenuates B.
    // → B channel IS attenuated in Phase 2 (second sphere red also contributes)
    renderer.clearAllInstances()
    renderer.addSphereInstance(lowSphereTransform,  transparentBlue, Const.iorGlass)
    renderer.addSphereInstance(highSphereTransform, transparentRed,  Const.iorGlass)
    setupMultiSphereSceneBase(transparentShadows = true)
    val twoSphereImage = renderer.render(imageSize)
    val (_, _, twoSphereAvgB) =
      regionChannelAverages(twoSphereImage, imageSize, shadowRegion)

    withClue(
      f"Two-sphere shadow B=$twoSphereAvgB%.1f should be darker than " +
      f"one-sphere shadow B=$oneSphereAvgB%.1f (red sphere attenuates B in Phase 2)"
    ) {
      (oneSphereAvgB - twoSphereAvgB) should be > MIN_COLOR_TINT_DIFFERENCE
    }

  // ========== Test 12: Accumulation is order-independent ==========

  it should "produce the same shadow regardless of which colored sphere is hit first" in:
    val transparentBlue = withAlpha(OPAQUE_BLUE, transparentAlpha)
    val transparentRed  = withAlpha(OPAQUE_RED,  transparentAlpha)

    // Ordering A: blue sphere at low position (hit first), red at high (hit second)
    renderer.clearAllInstances()
    renderer.addSphereInstance(lowSphereTransform,  transparentBlue, Const.iorGlass)
    renderer.addSphereInstance(highSphereTransform, transparentRed,  Const.iorGlass)
    setupMultiSphereSceneBase(transparentShadows = true)
    val imageA        = renderer.render(imageSize)
    val shadowRegion  = findShadowRegion(imageA)
    val (rA, gA, bA)  = regionChannelAverages(imageA, imageSize, shadowRegion)

    // Ordering B: red sphere at low position (hit first), blue at high (hit second)
    // Same sphere positions, colors swapped → same shadow tint by commutativity.
    renderer.clearAllInstances()
    renderer.addSphereInstance(lowSphereTransform,  transparentRed,  Const.iorGlass)
    renderer.addSphereInstance(highSphereTransform, transparentBlue, Const.iorGlass)
    setupMultiSphereSceneBase(transparentShadows = true)
    val imageB       = renderer.render(imageSize)
    val (rB, gB, bB) = regionChannelAverages(imageB, imageSize, shadowRegion)

    withClue(
      f"Shadow A (blue-low): R=$rA%.1f G=$gA%.1f B=$bA%.1f | " +
      f"Shadow B (red-low): R=$rB%.1f G=$gB%.1f B=$bB%.1f — " +
      "Multiplicative accumulation is commutative: shadows should match within tolerance"
    ) {
      math.abs(rA - rB) should be < ACHROMATIC_CHANNEL_TOLERANCE
      math.abs(gA - gB) should be < ACHROMATIC_CHANNEL_TOLERANCE
      math.abs(bA - bB) should be < ACHROMATIC_CHANNEL_TOLERANCE
    }

  // ========== Test 13: Opaque object behind transparent still casts full shadow ==========

  it should "cast darker shadow when opaque sphere is behind transparent sphere" in:
    // In colored-shadow mode, setShadowPayload computes atten = alpha*(1-color).
    // Opaque BLACK (color=0,0,0) gives atten=1.0 → shadow_factor=0 (fully dark).
    // Opaque WHITE would give atten=0 → no shadow (by design; see test 10 note).
    val transparentBlue = withAlpha(OPAQUE_BLUE, transparentAlpha)
    val opaqueBlack     = Color(0.0f, 0.0f, 0.0f, 1.0f)

    // Transparent only: shadow ray passes through blue → shadow_b=1.0 (B fully lit),
    // shadow_r = shadow_g = 0.2 (R,G attenuated).
    renderer.clearAllInstances()
    renderer.addSphereInstance(lowSphereTransform, transparentBlue, Const.iorGlass)
    setupMultiSphereSceneBase(transparentShadows = true)
    val transparentOnlyImage = renderer.render(imageSize)
    val shadowRegion         = findShadowRegion(transparentOnlyImage)
    // Compare B channel: blue sphere does NOT attenuate B (shadow_b=1.0), so B stays
    // at full lit-floor brightness even inside the shadow circle.
    val (_, _, avgB_transparent) = regionChannelAverages(transparentOnlyImage, imageSize, shadowRegion)

    // Transparent (low) + opaque black (high): shadow ray passes through blue (anyhit
    // accumulates), then hits opaque black (closesthit overwrites payload with shadow=0).
    // Result: floor B is blocked down to ambient-only (shadow_b=0).
    renderer.clearAllInstances()
    renderer.addSphereInstance(lowSphereTransform,  transparentBlue, Const.iorGlass)
    renderer.addSphereInstance(highSphereTransform, opaqueBlack,     Const.iorGlass)
    setupMultiSphereSceneBase(transparentShadows = true)
    val opaqueBackImage       = renderer.render(imageSize)
    val (_, _, avgB_opaqueBack) = regionChannelAverages(opaqueBackImage, imageSize, shadowRegion)

    withClue(
      f"Transparent-only B channel=$avgB_transparent%.1f should be brighter than " +
      f"transparent+opaque-black B=$avgB_opaqueBack%.1f: blue sphere passes B (shadow_b=1), " +
      "opaque black behind overwrites shadow to 0 (closesthit) blocking all B"
    ) {
      avgB_transparent should be > avgB_opaqueBack + MIN_COLOR_TINT_DIFFERENCE
    }
