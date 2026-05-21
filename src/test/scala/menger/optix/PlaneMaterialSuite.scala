package menger.optix

import menger.common.Color
import menger.common.ImageSize
import menger.common.Vector
import menger.optix.ThresholdConstants.TEST_IMAGE_SIZE
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers


/** Tests for plane material support (Sprint 13.1).
  *
  * Tests verify that material parameters are passed through the full pipeline
  * and affect rendering output.
  */
class PlaneMaterialSuite extends AnyFlatSpec with Matchers with RendererFixture:

  OptiXRenderer.isLibraryLoaded shouldBe true

  private val floorY       = -2.0f
  private val floorAxis    = 1
  private val floorColor   = Color(0.8f, 0.8f, 0.8f, 1.0f)
  private val smallImage   = ImageSize(80, 60)

  // Default matte material parameters (give original behavior)
  private val matteRoughness  = 1.0f
  private val matteMetallic   = 0.0f
  private val matteSpecular   = 0.5f
  private val matteEmission   = 0.0f
  private val noTexture       = -1

  // Chrome material parameters
  private val chromeRoughness = 0.0f
  private val chromeMetallic  = 1.0f
  private val chromeSpecular  = 1.0f
  private val chromeEmission  = 0.0f

  // Emissive material parameters — keep small enough to avoid uint overflow with default lighting
  private val emissionBrightness = 0.3f

  private def setupScene(renderer: OptiXRenderer): Unit =
    renderer.setCamera(
      Vector[3](0.0f, 1.0f, 4.0f),
      Vector[3](0.0f, -1.5f, 0.0f),
      Vector[3](0.0f, 1.0f, 0.0f),
      60.0f
    )
    renderer.setLight(Vector[3](0.5f, 1.0f, -0.5f), 1.0f)
    renderer.setSphere(Vector[3](0.0f, 0.0f, 0.0f), 0.3f)
    renderer.setSphereColor(Color(0.7f, 0.7f, 0.7f, 1.0f))

  "addPlaneSolidColorWithMaterial" should "not throw when called with matte parameters" in:
    setupScene(renderer)
    renderer.clearPlanes()
    noException should be thrownBy renderer.addPlaneSolidColorWithMaterial(
      floorAxis, positive = false, floorY,
      floorColor,
      Material.matte(floorColor),
      noTexture
    )
    noException should be thrownBy renderer.render(smallImage)

  it should "not throw when called with chrome parameters" in:
    setupScene(renderer)
    renderer.clearPlanes()
    noException should be thrownBy renderer.addPlaneSolidColorWithMaterial(
      floorAxis, positive = false, floorY,
      floorColor,
      Material(
        color    = floorColor,
        roughness = chromeRoughness,
        metallic  = chromeMetallic,
        specular  = chromeSpecular,
        emission  = chromeEmission
      ),
      noTexture
    )
    noException should be thrownBy renderer.render(smallImage)

  it should "not throw when called with emissive parameters" in:
    setupScene(renderer)
    renderer.clearPlanes()
    noException should be thrownBy renderer.addPlaneSolidColorWithMaterial(
      floorAxis, positive = false, floorY,
      floorColor,
      Material(color = floorColor, emission = emissionBrightness),
      noTexture
    )
    noException should be thrownBy renderer.render(smallImage)

  "addPlaneCheckerColorsWithMaterial" should "not throw when called with matte parameters" in:
    setupScene(renderer)
    renderer.clearPlanes()
    val white = Color(1.0f, 1.0f, 1.0f, 1.0f)
    val black = Color(0.1f, 0.1f, 0.1f, 1.0f)
    noException should be thrownBy renderer.addPlaneCheckerColorsWithMaterial(
      floorAxis, positive = false, floorY,
      white, black,
      Material.matte(white),
      noTexture
    )
    noException should be thrownBy renderer.render(smallImage)

  "plane material emission" should "produce a brighter image than matte plane" in:
    setupScene(renderer)
    renderer.clearPlanes()
    renderer.addPlaneSolidColorWithMaterial(
      floorAxis, positive = false, floorY,
      floorColor,
      Material(color = floorColor, roughness = matteRoughness, metallic = matteMetallic,
               specular = matteSpecular, emission = matteEmission),
      noTexture
    )
    val matteImage = renderer.render(TEST_IMAGE_SIZE)
    val matteBrightness = averageBrightness(matteImage)

    renderer.clearPlanes()
    renderer.addPlaneSolidColorWithMaterial(
      floorAxis, positive = false, floorY,
      floorColor,
      Material(color = floorColor, roughness = matteRoughness, metallic = matteMetallic,
               specular = matteSpecular, emission = emissionBrightness),
      noTexture
    )
    val emissiveImage = renderer.render(TEST_IMAGE_SIZE)
    val emissiveBrightness = averageBrightness(emissiveImage)

    emissiveBrightness should be > matteBrightness

  "plane material chrome vs matte" should "render differently" in:
    setupScene(renderer)
    renderer.clearPlanes()
    renderer.addPlaneSolidColorWithMaterial(
      floorAxis, positive = false, floorY,
      floorColor,
      Material(color = floorColor, roughness = matteRoughness, metallic = matteMetallic,
               specular = matteSpecular, emission = matteEmission),
      noTexture
    )
    val matteImage = renderer.render(TEST_IMAGE_SIZE)

    renderer.clearPlanes()
    renderer.addPlaneSolidColorWithMaterial(
      floorAxis, positive = false, floorY,
      floorColor,
      Material(color = floorColor, roughness = chromeRoughness, metallic = chromeMetallic,
               specular = chromeSpecular, emission = chromeEmission),
      noTexture
    )
    val chromeImage = renderer.render(TEST_IMAGE_SIZE)

    // Images must differ by at least one step (the specular highlight introduces visible difference)
    val diff = pixelDifference(matteImage, chromeImage)
    diff should be > 0.0

  "plane material chrome preset" should "render without crash" in:
    setupScene(renderer)
    renderer.clearPlanes()
    noException should be thrownBy {
      renderer.addPlaneSolidColorWithMaterial(
        floorAxis, positive = false, floorY,
        floorColor,
        Material.Chrome.copy(color = floorColor),
        noTexture
      )
      renderer.render(smallImage)
    }

  "plane solid color with material and checker with material" should "both render without crash" in:
    setupScene(renderer)

    // Solid color with matte
    renderer.clearPlanes()
    noException should be thrownBy {
      renderer.addPlaneSolidColorWithMaterial(
        floorAxis, positive = false, floorY,
        floorColor,
        Material.matte(floorColor),
        noTexture
      )
      renderer.render(smallImage)
    }

    // Checker with chrome
    renderer.clearPlanes()
    val white = Color(1.0f, 1.0f, 1.0f, 1.0f)
    val black = Color(0.1f, 0.1f, 0.1f, 1.0f)
    noException should be thrownBy {
      renderer.addPlaneCheckerColorsWithMaterial(
        floorAxis, positive = false, floorY,
        white, black,
        Material.Chrome.copy(color = white),
        noTexture
      )
      renderer.render(smallImage)
    }

  "solid chrome plane via material colour" should "render differently from plain default plane" in:
    // Regression: SceneConfigurator case None branch must use material.color as solid floor colour,
    // not the default checker gray.  The fix routes addPlaneSolidColorWithMaterial(mat.color, mat).
    setupScene(renderer)

    renderer.clearPlanes()
    renderer.addPlane(floorAxis, positive = false, floorY)
    val plainImage = renderer.render(TEST_IMAGE_SIZE)

    renderer.clearPlanes()
    renderer.addPlaneSolidColorWithMaterial(
      floorAxis, positive = false, floorY,
      Material.Chrome.color,
      Material(color = Material.Chrome.color, roughness = chromeRoughness, metallic = chromeMetallic,
               specular = chromeSpecular, emission = chromeEmission),
      noTexture
    )
    val chromeImage = renderer.render(TEST_IMAGE_SIZE)

    val diff = pixelDifference(plainImage, chromeImage)
    diff should be > 0.0

  "metallic plane" should "reflect scene geometry" in:
    // Regression: metallic planes now trace a reflected ray (miss_plane.cu metallic path).
    // A chrome floor in a scene with a sphere should show the sphere's reflection;
    // a matte floor should not — producing a measurable pixel difference.
    setupScene(renderer)

    renderer.clearPlanes()
    renderer.addPlaneCheckerColorsWithMaterial(
      floorAxis, positive = false, floorY,
      floorColor, floorColor,
      Material(color = floorColor, roughness = matteRoughness, metallic = matteMetallic,
               specular = matteSpecular, emission = matteEmission),
      noTexture
    )
    val matteImage = renderer.render(TEST_IMAGE_SIZE)

    renderer.clearPlanes()
    renderer.addPlaneCheckerColorsWithMaterial(
      floorAxis, positive = false, floorY,
      floorColor, floorColor,
      Material(color = floorColor, roughness = chromeRoughness, metallic = chromeMetallic,
               specular = chromeSpecular, emission = chromeEmission),
      noTexture
    )
    val chromeImage = renderer.render(TEST_IMAGE_SIZE)

    val diff = pixelDifference(matteImage, chromeImage)
    diff should be > 1.0  // reflections produce substantial pixel difference

  // --- helpers ---------------------------------------------------------------

  private def averageBrightness(image: Array[Byte]): Double =
    val pixels = image.length / 4
    (0 until pixels).map { i =>
      val offset = i * 4
      ((image(offset) & 0xFF) + (image(offset + 1) & 0xFF) + (image(offset + 2) & 0xFF)) / 3.0
    }.sum / pixels

  private def pixelDifference(a: Array[Byte], b: Array[Byte]): Double =
    require(a.length == b.length, "Images must have the same size for comparison")
    val pixels = a.length / 4
    (0 until pixels).map { i =>
      val off = i * 4
      val dr  = math.abs((a(off) & 0xFF) - (b(off) & 0xFF))
      val dg  = math.abs((a(off + 1) & 0xFF) - (b(off + 1) & 0xFF))
      val db  = math.abs((a(off + 2) & 0xFF) - (b(off + 2) & 0xFF))
      (dr + dg + db) / 3.0
    }.sum / pixels
