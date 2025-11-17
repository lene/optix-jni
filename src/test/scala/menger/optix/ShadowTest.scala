package menger.optix

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ImageMatchers.*
import ShadowValidation.*
import scala.math.abs

class ShadowTest extends AnyFlatSpec with Matchers with RendererFixture:

  // Helper method for standard shadow scene setup
  def setupShadowScene(
    sphereAlpha: Float = 1.0f,
    lightDir: Array[Float] = Array(0.5f, 0.5f, -0.5f),
    sphereRadius: Float = 0.5f,
    sphereY: Float = 0.0f,
    planeY: Float = -0.6f
  ): Unit =
    renderer.setSphere(0.0f, sphereY, 0.0f, sphereRadius)
    renderer.setSphereColor(0.75f, 0.75f, 0.75f, sphereAlpha)
    renderer.setIOR(1.5f)
    renderer.setScale(1.0f)
    renderer.setPlane(1, true, planeY)
    renderer.setPlaneSolidColor(true)  // Use solid color for shadow visibility
    renderer.setCamera(
      Array(0.0f, 0.0f, 5.0f),
      Array(0.0f, -0.3f, 0.0f),
      Array(0.0f, 1.0f, 0.0f),
      45.0f
    )
    renderer.setLight(lightDir, 1.0f)

  "Shadow rays" should "create darker shadows when enabled" in:
    // Setup basic scene
    renderer.setSphere(0.0f, 0.0f, 0.0f, 0.5f)
    renderer.setSphereColor(0.75f, 0.75f, 0.75f, 1.0f)  // Opaque sphere
    renderer.setIOR(1.5f)
    renderer.setScale(1.0f)
    renderer.setPlane(1, true, -0.6f)  // Y-axis plane below sphere
    renderer.setPlaneSolidColor(true)  // Use solid color for shadow visibility

    val eye = Array(0.0f, 0.0f, 5.0f)
    val lookAt = Array(0.0f, -0.3f, 0.0f)
    val up = Array(0.0f, 1.0f, 0.0f)
    renderer.setCamera(eye, lookAt, up, 45.0f)

    val lightDir = Array(0.5f, 0.5f, -0.5f)
    renderer.setLight(lightDir, 1.0f)

    // Render without shadows
    renderer.setShadows(false)
    val imageWithoutShadows = renderer.render(800, 600).get

    // Render with shadows
    renderer.setShadows(true)
    val imageWithShadows = renderer.render(800, 600).get

    // Images should be different (shadows should darken the plane)
    java.util.Arrays.equals(imageWithoutShadows, imageWithShadows) shouldBe false

  it should "respect material transparency" in:
    // Setup scene
    renderer.setSphere(0.0f, 0.0f, 0.0f, 0.5f)
    renderer.setIOR(1.5f)
    renderer.setScale(1.0f)
    renderer.setPlane(1, true, -0.6f)
    renderer.setPlaneSolidColor(true)  // Use solid color for shadow visibility

    val eye = Array(0.0f, 0.0f, 5.0f)
    val lookAt = Array(0.0f, -0.3f, 0.0f)
    val up = Array(0.0f, 1.0f, 0.0f)
    renderer.setCamera(eye, lookAt, up, 45.0f)

    val lightDir = Array(0.5f, 0.5f, -0.5f)
    renderer.setLight(lightDir, 1.0f)
    renderer.setShadows(true)

    // Render with fully transparent sphere (alpha=0.0)
    renderer.setSphereColor(0.75f, 0.75f, 0.75f, 0.0f)
    val transparentImage = renderer.render(800, 600).get

    // Render with opaque sphere (alpha=1.0)
    renderer.setSphereColor(0.75f, 0.75f, 0.75f, 1.0f)
    val opaqueImage = renderer.render(800, 600).get

    // Images should be different (opaque sphere casts darker shadow)
    java.util.Arrays.equals(transparentImage, opaqueImage) shouldBe false

  // ========== GRADUATED TRANSPARENCY TESTS ==========

  "Shadow intensity" should "scale with material alpha" in:
    val alphaValues = List(0.0f, 0.25f, 0.5f, 0.75f, 1.0f)
    val measurements = alphaValues.map { alpha =>
      setupShadowScene(sphereAlpha = alpha)
      renderer.setShadows(true)
      val image = renderer.render(800, 600).get

      // Find actual shadow location
      val darkestRegion = detectDarkestRegion(image, 800, 600, gridSize = 8)
      val shadowBright = regionBrightness(image, 800, 600, darkestRegion)

      (alpha, shadowBright)
    }

    // Verify monotonic trend: higher alpha → darker shadow (lower brightness)
    measurements.sliding(2).foreach { case List((a1, b1), (a2, b2)) =>
      withClue(f"Shadow should darken with alpha: α=$a1%.2f→$a2%.2f, brightness=$b1%.1f→$b2%.1f") {
        b2 should be <= b1
      }
    }

    // Alpha=0.0 should have bright shadow region (minimal darkening)
    val (_, bright0) = measurements.head
    bright0 should be > 130.0

    // Alpha=1.0 should have dark shadow (significant darkening)
    val (_, bright1) = measurements.last
    bright1 should be < 95.0

  it should "produce no visible shadow for fully transparent sphere (alpha=0.0)" in:
    // First render opaque to find shadow region
    setupShadowScene(sphereAlpha = 1.0f)
    renderer.setShadows(true)
    val imageOpaque = renderer.render(800, 600).get
    val shadowRegion = detectDarkestRegion(imageOpaque, 800, 600, gridSize = 8)

    // Measure that same region in transparent image
    setupShadowScene(sphereAlpha = 0.0f)
    renderer.setShadows(true)
    val imageTransparent = renderer.render(800, 600).get

    val brightTransparent = regionBrightness(imageTransparent, 800, 600, shadowRegion)
    val brightOpaque = regionBrightness(imageOpaque, 800, 600, shadowRegion)

    // Transparent sphere should have brighter "shadow" (minimal darkening)
    withClue(f"Transparent=$brightTransparent%.1f should be > opaque=$brightOpaque%.1f * 1.3") {
      brightTransparent should be > (brightOpaque * 1.3)
    }

  it should "produce maximum shadow for fully opaque sphere (alpha=1.0)" in:
    setupShadowScene(sphereAlpha = 1.0f)

    // Compare shadows OFF vs ON
    renderer.setShadows(false)
    val imageOff = renderer.render(800, 600).get

    renderer.setShadows(true)
    val imageOn = renderer.render(800, 600).get

    // Find darkest region with shadows ON (actual shadow location)
    val darkestOn = detectDarkestRegion(imageOn, 800, 600, gridSize = 8)

    val brightOff = regionBrightness(imageOff, 800, 600, darkestOn)
    val brightOn = regionBrightness(imageOn, 800, 600, darkestOn)

    // With shadows ON, should be significantly darker than without shadows
    brightOn should be < (brightOff * 0.65)

  it should "produce intermediate shadow for semi-transparent sphere (alpha=0.5)" in:
    // First render opaque to find shadow region
    setupShadowScene(sphereAlpha = 1.0f)
    renderer.setShadows(true)
    val imageOpaque = renderer.render(800, 600).get
    val shadowRegion = detectDarkestRegion(imageOpaque, 800, 600, gridSize = 8)

    // Measure that same region in all alpha values
    setupShadowScene(sphereAlpha = 0.0f)
    renderer.setShadows(true)
    val imageTransparent = renderer.render(800, 600).get

    setupShadowScene(sphereAlpha = 0.5f)
    renderer.setShadows(true)
    val imageHalf = renderer.render(800, 600).get

    val brightOpaque = regionBrightness(imageOpaque, 800, 600, shadowRegion)
    val brightHalf = regionBrightness(imageHalf, 800, 600, shadowRegion)
    val brightTransparent = regionBrightness(imageTransparent, 800, 600, shadowRegion)

    // Semi-transparent shadow should be: opaque (darkest) < half < transparent (lightest)
    withClue(f"Expected opaque=$brightOpaque%.1f < half=$brightHalf%.1f < transparent=$brightTransparent%.1f") {
      brightOpaque should be < brightHalf
      brightHalf should be < brightTransparent
    }

  it should "have shadow intensity proportional to alpha for alpha=0.25" in:
    setupShadowScene(sphereAlpha = 0.25f)
    renderer.setShadows(true)
    val image025 = renderer.render(800, 600).get

    setupShadowScene(sphereAlpha = 1.0f)
    renderer.setShadows(true)
    val image100 = renderer.render(800, 600).get

    val bright025 = regionBrightness(image025, 800, 600, detectDarkestRegion(image025, 800, 600))
    val bright100 = regionBrightness(image100, 800, 600, detectDarkestRegion(image100, 800, 600))

    // alpha=0.25 should produce lighter shadow (higher brightness) than alpha=1.0
    bright025 should be > bright100

  it should "have shadow intensity proportional to alpha for alpha=0.75" in:
    setupShadowScene(sphereAlpha = 0.75f)
    renderer.setShadows(true)
    val image075 = renderer.render(800, 600).get

    setupShadowScene(sphereAlpha = 1.0f)
    renderer.setShadows(true)
    val image100 = renderer.render(800, 600).get

    val bright075 = regionBrightness(image075, 800, 600, detectDarkestRegion(image075, 800, 600))
    val bright100 = regionBrightness(image100, 800, 600, detectDarkestRegion(image100, 800, 600))

    // alpha=0.75 should be close to alpha=1.0 but slightly lighter (higher brightness)
    // Allow some tolerance since they're close in value
    withClue(f"alpha=0.75 brightness=$bright075%.1f should be >= alpha=1.0 brightness=$bright100%.1f") {
      bright075 should be >= (bright100 * 0.90)
      bright075 should be <= (bright100 * 1.10)
    }

  // ========== LIGHT DIRECTION TESTS ==========

  "Shadows" should "appear on opposite side of sphere from light" in:
    setupShadowScene(lightDir = Array(1.0f, 0.5f, 0.0f))  // Light direction toward +X
    renderer.setShadows(true)
    val imageRight = renderer.render(800, 600).get

    setupShadowScene(lightDir = Array(-1.0f, 0.5f, 0.0f))  // Light direction toward -X
    renderer.setShadows(true)
    val imageLeft = renderer.render(800, 600).get

    val darkestRight = detectDarkestRegion(imageRight, 800, 600, gridSize = 8)
    val darkestLeft = detectDarkestRegion(imageLeft, 800, 600, gridSize = 8)

    val shadowXRight = (darkestRight.x0 + darkestRight.x1) / 2
    val shadowXLeft = (darkestLeft.x0 + darkestLeft.x1) / 2

    // Shadow position should differ based on light direction
    abs(shadowXRight - shadowXLeft) should be > 50

  it should "cast shadow directly below sphere for overhead light" in:
    setupShadowScene(lightDir = Array(0.0f, 1.0f, 0.0f))
    renderer.setShadows(true)
    val image = renderer.render(800, 600).get

    val darkest = detectDarkestRegion(image, 800, 600, gridSize = 10)
    val shadowCenterX = (darkest.x0 + darkest.x1) / 2
    val imageCenterX = 800 / 2

    // Shadow should be roughly centered (within 15% of image width)
    abs(shadowCenterX - imageCenterX) should be < (800 * 0.15).toInt

  it should "cast shadow that shifts with light direction (X-axis)" in:
    setupShadowScene(lightDir = Array(1.0f, 0.5f, 0.0f))
    renderer.setShadows(true)
    val imageRight = renderer.render(800, 600).get

    setupShadowScene(lightDir = Array(-1.0f, 0.5f, 0.0f))
    renderer.setShadows(true)
    val imageLeft = renderer.render(800, 600).get

    val darkestRight = detectDarkestRegion(imageRight, 800, 600, gridSize = 8)
    val darkestLeft = detectDarkestRegion(imageLeft, 800, 600, gridSize = 8)

    val shadowXRight = (darkestRight.x0 + darkestRight.x1) / 2
    val shadowXLeft = (darkestLeft.x0 + darkestLeft.x1) / 2

    // Shadow position should shift significantly with light direction change
    abs(shadowXRight - shadowXLeft) should be > 40

  it should "respond to light angle changes" in:
    setupShadowScene(lightDir = Array(0.7f, 0.7f, 0.0f))
    renderer.setShadows(true)
    val image1 = renderer.render(800, 600).get

    setupShadowScene(lightDir = Array(-0.7f, 0.7f, 0.0f))
    renderer.setShadows(true)
    val image2 = renderer.render(800, 600).get

    // Images should be different
    java.util.Arrays.equals(image1, image2) shouldBe false

  it should "have minimal shadow contrast for light behind camera" in:
    setupShadowScene(lightDir = Array(0.0f, 0.0f, 1.0f))  // Light from camera direction
    renderer.setShadows(true)
    val imageOn = renderer.render(800, 600).get

    renderer.setShadows(false)
    val imageOff = renderer.render(800, 600).get

    // Find darkest region with shadows on
    val darkest = detectDarkestRegion(imageOn, 800, 600, gridSize = 8)
    val brightOn = regionBrightness(imageOn, 800, 600, darkest)
    val brightOff = regionBrightness(imageOff, 800, 600, darkest)

    // Shadow should be minimal (brightness similar with shadows on/off)
    brightOn should be > (brightOff * 0.85)

  it should "handle grazing light angle without artifacts" in:
    setupShadowScene(lightDir = Array(1.0f, 0.1f, 0.0f))
    renderer.setShadows(true)

    noException should be thrownBy {
      val image = renderer.render(800, 600).get
      image.length shouldBe 800 * 600 * 4
    }

  it should "handle light from below gracefully (degenerate case)" in:
    setupShadowScene(lightDir = Array(0.0f, -1.0f, 0.0f))
    renderer.setShadows(true)

    noException should be thrownBy {
      val image = renderer.render(800, 600).get
      image.length shouldBe 800 * 600 * 4
    }

  // ========== GEOMETRIC VALIDATION TESTS ==========

  "Shadow position" should "shift with horizontal light direction" in:
    setupShadowScene(lightDir = Array(1.0f, 0.5f, 0.0f))  // Right
    renderer.setShadows(true)
    val imageRight = renderer.render(800, 600).get
    val darkestRight = detectDarkestRegion(imageRight, 800, 600, gridSize = 8)

    setupShadowScene(lightDir = Array(-1.0f, 0.5f, 0.0f))  // Left
    renderer.setShadows(true)
    val imageLeft = renderer.render(800, 600).get
    val darkestLeft = detectDarkestRegion(imageLeft, 800, 600, gridSize = 8)

    val xRight = (darkestRight.x0 + darkestRight.x1) / 2
    val xLeft = (darkestLeft.x0 + darkestLeft.x1) / 2

    // Shadow position should shift between left and right light directions
    abs(xRight - xLeft) should be > 40

  it should "change position with different light angles" in:
    // Light from right
    setupShadowScene(lightDir = Array(1.0f, 0.5f, 0.0f))
    renderer.setShadows(true)
    val imageRight = renderer.render(800, 600).get
    val darkestRight = detectDarkestRegion(imageRight, 800, 600, gridSize = 8)

    // Light from left
    setupShadowScene(lightDir = Array(-1.0f, 1.0f, 0.0f))
    renderer.setShadows(true)
    val imageLeft = renderer.render(800, 600).get
    val darkestLeft = detectDarkestRegion(imageLeft, 800, 600, gridSize = 8)

    // Shadow position should differ between left and right light
    val posRight = (darkestRight.x0 + darkestRight.x1) / 2
    val posLeft = (darkestLeft.x0 + darkestLeft.x1) / 2
    abs(posRight - posLeft) should be > 10

  it should "remain centered for overhead light" in:
    setupShadowScene(lightDir = Array(0.0f, 1.0f, 0.0f))
    renderer.setShadows(true)
    val image = renderer.render(800, 600).get
    val darkest = detectDarkestRegion(image, 800, 600, gridSize = 10)

    val centerX = 800 / 2
    val shadowCenterX = (darkest.x0 + darkest.x1) / 2

    // Shadow should be roughly centered (within 20% of image width)
    abs(shadowCenterX - centerX) should be < (800 * 0.2).toInt

  "Shadow coverage" should "increase with sphere radius" in:
    val radii = List(0.3f, 0.5f, 0.8f)
    val measurements = radii.map { radius =>
      setupShadowScene(sphereRadius = radius, sphereY = 0.0f)
      renderer.setShadows(true)
      val image = renderer.render(800, 600).get
      val darkest = detectDarkestRegion(image, 800, 600, gridSize = 8)
      val brightness = regionBrightness(image, 800, 600, darkest)
      (radius, brightness, darkest.area)
    }

    // Larger sphere should create darker shadow (lower brightness)
    measurements.sliding(2).foreach { case List((r1, b1, _), (r2, b2, _)) =>
      withClue(f"Larger sphere should cast darker shadow: r=$r1%.1f→$r2%.1f, brightness=$b1%.1f→$b2%.1f") {
        b2 should be <= (b1 * 1.05)  // Allow 5% tolerance
      }
    }

  // ========== EDGE CASE TESTS ==========

  it should "handle sphere very close to plane without artifacts" in:
    setupShadowScene(sphereRadius = 0.5f, sphereY = -0.05f, planeY = -0.6f)

    noException should be thrownBy {
      renderer.setShadows(false)
      val imageOff = renderer.render(800, 600).get

      renderer.setShadows(true)
      val imageOn = renderer.render(800, 600).get

      // Should still have visible shadow
      val darkest = detectDarkestRegion(imageOn, 800, 600, gridSize = 8)
      val brightOn = regionBrightness(imageOn, 800, 600, darkest)
      val brightOff = regionBrightness(imageOff, 800, 600, darkest)

      brightOn should be < (brightOff * 0.8)
    }

  it should "handle very small sphere" in:
    setupShadowScene(sphereRadius = 0.1f)
    renderer.setShadows(true)

    noException should be thrownBy {
      val image = renderer.render(800, 600).get
      val darkest = detectDarkestRegion(image, 800, 600, gridSize = 10)
      darkest.area should be > 0
    }

  it should "handle very large sphere" in:
    setupShadowScene(sphereRadius = 1.5f, sphereY = 0.5f)
    renderer.setShadows(true)

    noException should be thrownBy {
      val image = renderer.render(800, 600).get
      val shadowRegion = Region.bottomCenter(800, 600, fraction = 0.4)
      val brightness = regionBrightness(image, 800, 600, shadowRegion)

      // Should be noticeably darker
      brightness should be < 150.0  // Out of 255
    }

  it should "handle sphere far from plane" in:
    setupShadowScene(sphereY = 2.0f, planeY = -2.0f)

    noException should be thrownBy {
      renderer.setShadows(false)
      val imageOff = renderer.render(800, 600).get

      renderer.setShadows(true)
      val imageOn = renderer.render(800, 600).get

      val darkest = detectDarkestRegion(imageOn, 800, 600, gridSize = 8)
      val brightOn = regionBrightness(imageOn, 800, 600, darkest)
      val brightOff = regionBrightness(imageOff, 800, 600, darkest)

      // Some shadow contrast should exist
      brightOn should be < brightOff
    }

  it should "handle sphere below plane gracefully (degenerate case)" in:
    setupShadowScene(sphereY = -1.0f, planeY = -0.6f)
    renderer.setShadows(true)

    noException should be thrownBy {
      val image = renderer.render(800, 600).get
      image.length shouldBe 800 * 600 * 4
    }

  it should "produce consistent results across multiple renders" in:
    setupShadowScene(sphereAlpha = 0.5f)
    renderer.setShadows(true)

    val image1 = renderer.render(800, 600).get
    val image2 = renderer.render(800, 600).get

    val darkest1 = detectDarkestRegion(image1, 800, 600, gridSize = 8)
    val darkest2 = detectDarkestRegion(image2, 800, 600, gridSize = 8)

    val bright1 = regionBrightness(image1, 800, 600, darkest1)
    val bright2 = regionBrightness(image2, 800, 600, darkest2)

    // Should be identical
    bright1 shouldBe bright2 +- 1.0  // Allow 1 unit variation due to floating point

  it should "have acceptable rendering performance with shadows enabled" in:
    setupShadowScene()

    // Measure without shadows
    renderer.setShadows(false)
    val startNoShadow = System.nanoTime()
    (0 until 10).foreach(_ => renderer.render(800, 600).get)
    val timeNoShadow = (System.nanoTime() - startNoShadow) / 10

    // Measure with shadows
    renderer.setShadows(true)
    val startWithShadow = System.nanoTime()
    (0 until 10).foreach(_ => renderer.render(800, 600).get)
    val timeWithShadow = (System.nanoTime() - startWithShadow) / 10

    val overhead = (timeWithShadow - timeNoShadow).toDouble / timeNoShadow

    // Should be less than 100% overhead (reasonable for quality improvement)
    overhead should be < 1.0
