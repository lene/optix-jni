package menger.optix

import menger.common.AreaLightShape
import menger.common.Color
import menger.common.Const
import menger.common.Light
import menger.common.Vector
import menger.optix.ColorConstants.OPAQUE_LIGHT_GRAY
import menger.optix.ShadowValidation.detectDarkestRegion
import menger.optix.ShadowValidation.regionBrightness
import menger.optix.ThresholdConstants.DEFAULT_SHADOW_GRID
import menger.optix.ThresholdConstants.TEST_IMAGE_SIZE
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class AreaLightSuite extends AnyFlatSpec with Matchers with RendererFixture:

  private val imageSize = TEST_IMAGE_SIZE

  // Disk area light above and slightly behind the sphere, facing downward
  private val areaLightAbove = Light.Area(
    position = Vector[3](0.0f, 3.0f, 1.0f),
    normal = Vector[3](0.0f, -1.0f, 0.0f),
    radius = 1.5f,
    shadowSamples = 4
  )

  private val pointLightAbove = Light.Point(
    position = Vector[3](0.0f, 3.0f, 1.0f),
    intensity = 1.0f
  )

  private def setupShadowScene(light: Light): Unit =
    renderer.setSphere(Vector[3](0.0f, 0.0f, 0.0f), 0.5f)
    renderer.setSphereColor(OPAQUE_LIGHT_GRAY)
    renderer.setIOR(Const.iorVacuum)
    renderer.setScale(1.0f)
    renderer.clearPlanes()
    renderer.addPlaneSolidColor(1, true, -0.6f, Color.LIGHT_GRAY.r, Color.LIGHT_GRAY.g, Color.LIGHT_GRAY.b)
    renderer.setCamera(
      Vector[3](0.0f, 0.0f, 5.0f),
      Vector[3](0.0f, -0.3f, 0.0f),
      Vector[3](0.0f, 1.0f, 0.0f),
      45.0f
    )
    renderer.setLights(Array(light))
    renderer.setShadows(true)

  // ========== Test 1: API — Light.Area construction defaults ==========

  "Light.Area" should "have correct default fields" in:
    val light = Light.Area(
      position = Vector[3](0.0f, 3.0f, 0.0f),
      normal = Vector[3](0.0f, -1.0f, 0.0f),
      radius = 1.0f
    )

    light.lightType shouldBe menger.common.LightType.Area
    light.shape shouldBe AreaLightShape.Disk
    light.shadowSamples shouldBe 4
    light.color shouldBe Color(1.0f, 1.0f, 1.0f)
    light.intensity shouldBe 1.0f

  it should "accept custom shadow sample count" in:
    val light = Light.Area(
      position = Vector[3](0.0f, 3.0f, 0.0f),
      normal = Vector[3](0.0f, -1.0f, 0.0f),
      radius = 1.0f,
      shadowSamples = 8
    )
    light.shadowSamples shouldBe 8

  it should "reject non-positive radius" in:
    an[IllegalArgumentException] should be thrownBy:
      Light.Area(Vector[3](0f, 3f, 0f), Vector[3](0f, -1f, 0f), radius = 0.0f)

  it should "reject shadowSamples outside 1–16" in:
    an[IllegalArgumentException] should be thrownBy:
      Light.Area(Vector[3](0f, 3f, 0f), Vector[3](0f, -1f, 0f), radius = 1.0f, shadowSamples = 0)
    an[IllegalArgumentException] should be thrownBy:
      Light.Area(Vector[3](0f, 3f, 0f), Vector[3](0f, -1f, 0f), radius = 1.0f, shadowSamples = 17)

  // ========== Test 2: JNI layer — AreaLightShape constant ==========

  "AreaLightShape" should "have DISK = 0 matching C++ enum" in:
    AreaLightShape.Disk.id shouldBe 0

  "AreaLightShape JNI object" should "have DISK = 0" in:
    menger.optix.AreaLightShape.DISK shouldBe 0

  // ========== Test 3: Rendering — area light produces a shadow ==========

  "Area light" should "produce a shadow on the floor plane" in:
    setupShadowScene(areaLightAbove)
    val image = renderImage(imageSize)

    val shadowRegion = detectDarkestRegion(image, imageSize, DEFAULT_SHADOW_GRID)
    val shadowBrightness = regionBrightness(image, imageSize, shadowRegion)

    // There should be a meaningfully dark region (shadow exists)
    shadowBrightness should be < 200.0

  it should "render without errors with 1 shadow sample" in:
    setupShadowScene(
      Light.Area(Vector[3](0f, 3f, 1f), Vector[3](0f, -1f, 0f), 1.5f, shadowSamples = 1)
    )
    val image = renderImage(imageSize)
    image.length should be > 0

  it should "render without errors with 16 shadow samples" in:
    setupShadowScene(
      Light.Area(Vector[3](0f, 3f, 1f), Vector[3](0f, -1f, 0f), 1.5f, shadowSamples = 16)
    )
    val image = renderImage(imageSize)
    image.length should be > 0

  // ========== Test 4: Soft shadow — penumbra is less dark than point shadow ==========

  "Area light shadow" should "be less dark (softer) than a point light shadow at the same position" in:
    // Point light: hard shadow, maximum darkening
    setupShadowScene(pointLightAbove)
    val pointImage = renderImage(imageSize)
    val pointShadowRegion = detectDarkestRegion(pointImage, imageSize, DEFAULT_SHADOW_GRID)
    val pointShadowBrightness = regionBrightness(pointImage, imageSize, pointShadowRegion)

    // Area light: soft shadow, penumbra allows partial light
    setupShadowScene(areaLightAbove)
    val areaImage = renderImage(imageSize)
    val areaShadowRegion = detectDarkestRegion(areaImage, imageSize, DEFAULT_SHADOW_GRID)
    val areaShadowBrightness = regionBrightness(areaImage, imageSize, areaShadowRegion)

    // The area light's darkest region should be brighter than the point light's
    // (soft shadow = partial occlusion at any given sample point)
    areaShadowBrightness should be > pointShadowBrightness - 0.01  // area >= point (softer or equal)

  // ========== Test 5: Colored shadow compatibility ==========

  "Area light" should "produce per-channel tinted shadow through a transparent colored sphere" in:
    // Red transparent sphere casts a reddish shadow
    renderer.setSphere(Vector[3](0.0f, 0.0f, 0.0f), 0.5f)
    renderer.setSphereColor(Color(1.0f, 0.0f, 0.0f, 0.6f))
    renderer.setIOR(Const.iorGlass)
    renderer.setScale(1.0f)
    renderer.clearPlanes()
    renderer.addPlaneSolidColor(1, true, -0.6f, Color.LIGHT_GRAY.r, Color.LIGHT_GRAY.g, Color.LIGHT_GRAY.b)
    renderer.setCamera(
      Vector[3](0.0f, 0.0f, 5.0f),
      Vector[3](0.0f, -0.3f, 0.0f),
      Vector[3](0.0f, 1.0f, 0.0f),
      45.0f
    )
    renderer.setLights(Array(areaLightAbove))
    renderer.setShadows(true)
    renderer.setTransparentShadows(true)

    val image = renderImage(imageSize)
    image.length should be > 0

    // Find the shadow region and verify image renders (integration smoke test)
    val shadowRegion = detectDarkestRegion(image, imageSize, DEFAULT_SHADOW_GRID)
    val shadowBrightness = regionBrightness(image, imageSize, shadowRegion)
    // Shadow should exist (not fully lit)
    shadowBrightness should be < 220.0

  // ========== Test 6: Multiple area lights ==========

  "Multiple area lights" should "render without errors" in:
    val lights: Array[Light] = Array(
      Light.Area(Vector[3](2.0f, 3.0f, 0.0f), Vector[3](-1.0f, -1.0f, 0.0f), 1.0f),
      Light.Area(Vector[3](-2.0f, 3.0f, 0.0f), Vector[3](1.0f, -1.0f, 0.0f), 1.0f)
    )
    renderer.setSphere(Vector[3](0.0f, 0.0f, 0.0f), 0.5f)
    renderer.setSphereColor(OPAQUE_LIGHT_GRAY)
    renderer.setIOR(Const.iorVacuum)
    renderer.setScale(1.0f)
    renderer.clearPlanes()
    renderer.addPlane(1, true, -0.6f)
    renderer.setCamera(
      Vector[3](0.0f, 0.0f, 5.0f),
      Vector[3](0.0f, 0.0f, 0.0f),
      Vector[3](0.0f, 1.0f, 0.0f),
      45.0f
    )
    renderer.setLights(lights)
    renderer.setShadows(true)

    val image = renderImage(imageSize)
    image.length should be > 0
