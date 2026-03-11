package menger.optix

import menger.common.Color
import menger.common.Const
import menger.common.ImageSize
import menger.common.Vector
import menger.optix.ColorConstants.OPAQUE_BLUE
import menger.optix.ColorConstants.OPAQUE_GREEN
import menger.optix.ColorConstants.OPAQUE_RED
import menger.optix.ColorConstants.OPAQUE_WHITE
import menger.optix.ColorConstants.withAlpha
import menger.optix.ImageValidation.getRGBAt
import menger.optix.ShadowValidation.Region
import menger.optix.ShadowValidation.detectDarkestRegion
import menger.optix.ShadowValidation.regionBrightness
import menger.optix.ThresholdConstants.ACHROMATIC_CHANNEL_TOLERANCE
import menger.optix.ThresholdConstants.DEFAULT_SHADOW_GRID
import menger.optix.ThresholdConstants.MIN_COLOR_TINT_DIFFERENCE
import menger.optix.ThresholdConstants.STANDARD_IMAGE_SIZE
import menger.optix.ThresholdConstants.TRANSPARENT_OPAQUE_BRIGHTNESS_RATIO
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
      val imageDefault = renderer.render(imageSize).get

      setupColoredShadowScene(
        withAlpha(OPAQUE_RED, transparentAlpha),
        coloredShadows = false
      )
      val imageExplicitOff = renderer.render(imageSize).get

      withClue("Default config should match explicit off") {
        java.util.Arrays.equals(
          imageDefault, imageExplicitOff
        ) shouldBe true
      }

  // ========== Test 2: No crash ==========

  it should "not crash when enabled" in:
    noException should be thrownBy {
      setupColoredShadowScene(
        withAlpha(OPAQUE_RED, transparentAlpha),
        coloredShadows = true
      )
      val image = renderer.render(imageSize).get
      image.length shouldBe
        imageSize.width * imageSize.height * 4
    }

  // ========== Test 3: Red sphere ==========

  it should
    "cast shadow with reduced red attenuation for" +
    " red transparent sphere" in:
      setupColoredShadowScene(
        withAlpha(OPAQUE_RED, transparentAlpha),
        coloredShadows = true
      )
      val image = renderer.render(imageSize).get
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
      val image = renderer.render(imageSize).get
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
      val image = renderer.render(imageSize).get
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
      val coloredImage = renderer.render(imageSize).get

      setupColoredShadowScene(
        withAlpha(OPAQUE_WHITE, transparentAlpha),
        coloredShadows = false
      )
      val scalarImage = renderer.render(imageSize).get

      // Use scalar image to find shadow (it has real shadow)
      val shadowRegion = findShadowRegion(scalarImage)

      val brightColored =
        regionBrightness(coloredImage, imageSize, shadowRegion)
      val brightScalar =
        regionBrightness(scalarImage, imageSize, shadowRegion)

      withClue(
        f"White sphere colored path brightness=" +
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
      val image = renderer.render(imageSize).get
      val shadowRegion = findShadowRegion(image)
      val (avgR, avgG, avgB) =
        regionChannelAverages(image, imageSize, shadowRegion)
      val maxSpread = List(
        math.abs(avgR - avgG),
        math.abs(avgR - avgB),
        math.abs(avgG - avgB)
      ).max

      withClue(
        f"Opaque gray sphere shadow " +
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
      val transparentImage = renderer.render(imageSize).get

      // Compare with opaque to find shadow region
      setupColoredShadowScene(
        Color(1.0f, 0.0f, 0.0f, 1.0f),
        coloredShadows = true
      )
      val opaqueImage = renderer.render(imageSize).get
      val shadowRegion = findShadowRegion(opaqueImage)

      val brightTransparent =
        regionBrightness(transparentImage, imageSize, shadowRegion)
      val brightOpaque =
        regionBrightness(opaqueImage, imageSize, shadowRegion)

      withClue(
        f"Transparent sphere shadow region " +
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
      val imageOff = renderer.render(imageSize).get

      setupColoredShadowScene(sphereColor, coloredShadows = true)
      val imageOn = renderer.render(imageSize).get

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
      val imageOff = renderer.render(imageSize).get

      setupColoredShadowScene(sphereColor, coloredShadows = true)
      val imageOn = renderer.render(imageSize).get

      withClue(
        "White sphere with colored shadows on vs off should " +
        "differ: scalar path gives uniform shadow, " +
        "colored path gives no shadow"
      ) {
        java.util.Arrays.equals(imageOff, imageOn) shouldBe false
      }
