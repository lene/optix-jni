package io.github.lene.optix

import io.github.lene.optix.ColorConstants.OPAQUE_BLUE
import io.github.lene.optix.ColorConstants.OPAQUE_GREEN
import io.github.lene.optix.ColorConstants.OPAQUE_RED
import io.github.lene.optix.ColorConstants.OPAQUE_WHITE
import io.github.lene.optix.ThresholdConstants.QUICK_TEST_SIZE
import io.github.lene.optix.ThresholdConstants.TEST_IMAGE_SIZE
import menger.common.Color
import menger.common.Vector
import menger.common.x
import menger.common.y
import menger.common.z
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers


class CylinderSuite extends AnyFlatSpec with Matchers with RendererFixture:

  // Ensure library is loaded before running tests
  OptiXRenderer.isLibraryLoaded shouldBe true

  // ========== Basic Cylinder Creation ==========

  "addCylinderInstance" should "return a valid instance ID for a simple cylinder" in:
    val p0 = Vector[3](-0.5f, 0.0f, 0.0f)
    val p1 = Vector[3](0.5f, 0.0f, 0.0f)
    val result = renderer.addCylinderInstance(p0, p1, 0.1f, Material.Chrome)
    result.value should be >= 0

  it should "return a valid instance ID with Material parameter" in:
    val p0 = Vector[3](0.0f, -0.5f, 0.0f)
    val p1 = Vector[3](0.0f, 0.5f, 0.0f)
    val result = renderer.addCylinderInstance(p0, p1, 0.1f, Material.Glass)
    result.value should be >= 0

  it should "return a valid instance ID with color and IOR parameters" in:
    val p0 = Vector[3](0.0f, 0.0f, -0.5f)
    val p1 = Vector[3](0.0f, 0.0f, 0.5f)
    val result = renderer.addCylinderInstance(p0, p1, 0.05f, OPAQUE_GREEN, 1.0f)
    result.value should be >= 0

  it should "support different cylinder radii" in:
    val p0 = Vector[3](0.0f, 0.0f, 0.0f)
    val p1 = Vector[3](1.0f, 0.0f, 0.0f)

    // Thin cylinder
    val thin = renderer.addCylinderInstance(p0, p1, 0.01f, Material.Chrome)
    thin.value should be >= 0

    // Thick cylinder
    val thick = renderer.addCylinderInstance(p0, p1, 0.5f, Material.Chrome)
    thick.value should be >= 0

  it should "support various orientations" in:
    // X-aligned
    val xAligned = renderer.addCylinderInstance(
      Vector[3](-1.0f, 0.0f, 0.0f),
      Vector[3](1.0f, 0.0f, 0.0f),
      0.1f, Material.Chrome
    )
    xAligned.value should be >= 0

    // Y-aligned (vertical)
    val yAligned = renderer.addCylinderInstance(
      Vector[3](0.0f, -1.0f, 0.0f),
      Vector[3](0.0f, 1.0f, 0.0f),
      0.1f, Material.Chrome
    )
    yAligned.value should be >= 0

    // Z-aligned
    val zAligned = renderer.addCylinderInstance(
      Vector[3](0.0f, 0.0f, -1.0f),
      Vector[3](0.0f, 0.0f, 1.0f),
      0.1f, Material.Chrome
    )
    zAligned.value should be >= 0

    // Diagonal
    val diagonal = renderer.addCylinderInstance(
      Vector[3](-1.0f, -1.0f, -1.0f),
      Vector[3](1.0f, 1.0f, 1.0f),
      0.1f, Material.Chrome
    )
    diagonal.value should be >= 0

  // ========== Multiple Cylinders ==========

  "Multiple cylinders" should "be addable in sequence" in:
    val ids = for i <- 0 until 5 yield
      val x = (i - 2) * 0.5f
      renderer.addCylinderInstance(
        Vector[3](x, -0.3f, 0.0f),
        Vector[3](x, 0.3f, 0.0f),
        0.05f,
        Material.Chrome
      )

    ids.foreach(_.value should be >= 0)
    ids.distinct.size shouldBe 5  // All unique IDs

  it should "work with mixed geometry (spheres and cylinders)" in:
    // Add a sphere first
    val sphereId = renderer.addSphereInstance(
      Vector[3](0.0f, 0.0f, 0.0f),
      Material.Glass
    )
    sphereId.value should be >= 0

    // Then add cylinders
    val cylinder1 = renderer.addCylinderInstance(
      Vector[3](-1.0f, 0.0f, 0.0f),
      Vector[3](-0.5f, 0.0f, 0.0f),
      0.05f,
      Material.Chrome
    )
    cylinder1.value should be >= 0

    val cylinder2 = renderer.addCylinderInstance(
      Vector[3](0.5f, 0.0f, 0.0f),
      Vector[3](1.0f, 0.0f, 0.0f),
      0.05f,
      Material.Chrome
    )
    cylinder2.value should be >= 0

  // ========== Cylinder Materials ==========

  "Cylinder materials" should "support film preset (translucent)" in:
    val result = renderer.addCylinderInstance(
      Vector[3](-0.5f, 0.0f, 0.0f),
      Vector[3](0.5f, 0.0f, 0.0f),
      0.1f,
      Material.Film
    )
    result.value should be >= 0

  it should "support parchment preset (semi-translucent)" in:
    val result = renderer.addCylinderInstance(
      Vector[3](-0.5f, 0.0f, 0.0f),
      Vector[3](0.5f, 0.0f, 0.0f),
      0.1f,
      Material.Parchment
    )
    result.value should be >= 0

  it should "support emissive materials" in:
    val emissiveMaterial = Material.Film.copy(emission = 5.0f)
    val result = renderer.addCylinderInstance(
      Vector[3](-0.5f, 0.0f, 0.0f),
      Vector[3](0.5f, 0.0f, 0.0f),
      0.1f,
      emissiveMaterial
    )
    result.value should be >= 0

  it should "support custom colors with emission" in:
    val cyanEmissive = Material(
      color = Color(0.0f, 1.0f, 1.0f, 1.0f),  // Cyan
      ior = 1.0f,
      roughness = 0.5f,
      metallic = 0.0f,
      specular = 0.5f,
      emission = 3.0f
    )
    val result = renderer.addCylinderInstance(
      Vector[3](-0.5f, 0.0f, 0.0f),
      Vector[3](0.5f, 0.0f, 0.0f),
      0.1f,
      cyanEmissive
    )
    result.value should be >= 0

  // ========== Cylinder Rendering ==========

  // ========== In-place update (interactive 4D rotation of edge-rendered objects) ==========

  private val Start0 = Vector[3](-0.8f, -0.3f, 0.0f)
  private val Start1 = Vector[3](0.8f, -0.3f, 0.0f)
  private val Moved0 = Vector[3](-0.4f, 0.2f, 0.3f)
  private val Moved1 = Vector[3](0.5f, 0.6f, -0.2f)
  private val StartRadius = 0.15f
  private val MovedRadius = 0.25f

  private def moveTo(id: InstanceId, p0: Vector[3], p1: Vector[3], radius: Float): Unit =
    renderer.updateCylinderInstances(
      Array(id), Array(p0.x, p0.y, p0.z), Array(p1.x, p1.y, p1.z), Array(radius)
    )

  private def renderFresh(p0: Vector[3], p1: Vector[3], radius: Float): Array[Byte] =
    renderer.clearAllInstances()
    renderer.addCylinderInstance(p0, p1, radius, Material(OPAQUE_GREEN, ior = 1.0f))
    renderImage(TEST_IMAGE_SIZE)

  "updateCylinderInstances" should "render exactly what adding the cylinder there renders" in:
    val id = renderer.addCylinderInstance(Start0, Start1, StartRadius, Material(OPAQUE_GREEN, ior = 1.0f))
    val before = renderImage(TEST_IMAGE_SIZE)  // builds the IAS, so the update refits it
    moveTo(id, Moved0, Moved1, MovedRadius)
    val updated = renderImage(TEST_IMAGE_SIZE)

    updated should not equal before
    updated shouldEqual renderFresh(Moved0, Moved1, MovedRadius)

  it should "also work before the first render has built the IAS" in:
    val id = renderer.addCylinderInstance(Start0, Start1, StartRadius, Material(OPAQUE_GREEN, ior = 1.0f))
    moveTo(id, Moved0, Moved1, MovedRadius)
    val updated = renderImage(TEST_IMAGE_SIZE)

    updated shouldEqual renderFresh(Moved0, Moved1, MovedRadius)

  it should "reject an instance id that is not a cylinder" in:
    val sphereId = renderer.addSphereInstance(Vector[3](0.0f, 0.0f, 0.0f), Material.Chrome)
    an[IllegalArgumentException] should be thrownBy moveTo(sphereId, Moved0, Moved1, MovedRadius)

  it should "reject an out-of-range instance id and a non-positive radius" in:
    val id = renderer.addCylinderInstance(Start0, Start1, StartRadius, Material.Chrome)
    an[IllegalArgumentException] should be thrownBy moveTo(InstanceId(id.value + 100), Moved0, Moved1, MovedRadius)
    an[IllegalArgumentException] should be thrownBy moveTo(id, Moved0, Moved1, 0.0f)

  it should "reject endpoint arrays that don't match the id count" in:
    val id = renderer.addCylinderInstance(Start0, Start1, StartRadius, Material.Chrome)
    an[IllegalArgumentException] should be thrownBy
      renderer.updateCylinderInstances(Array(id), Array(0f, 0f), Array(1f, 1f, 1f), Array(0.1f))

  "Cylinder rendering" should "produce non-empty image data" in:
    renderer.addCylinderInstance(
      Vector[3](-0.5f, 0.0f, 0.0f),
      Vector[3](0.5f, 0.0f, 0.0f),
      0.15f,
      Material.Chrome
    )

    val imageData = renderImage(QUICK_TEST_SIZE)
    imageData.length shouldBe ImageValidation.imageByteSize(QUICK_TEST_SIZE)

  it should "render visible cylinder geometry" in:
    // Add a large, bright cylinder that should be clearly visible
    renderer.addCylinderInstance(
      Vector[3](-0.8f, 0.0f, 0.0f),
      Vector[3](0.8f, 0.0f, 0.0f),
      0.3f,
      Material(OPAQUE_GREEN, ior = 1.0f)
    )

    val imageData = renderImage(TEST_IMAGE_SIZE)

    // Image should have brightness variation (not all black or all white)
    val stdDev = ImageValidation.brightnessStdDev(imageData, TEST_IMAGE_SIZE)
    stdDev should be > 5.0  // Should have visible content

  it should "render multiple colored cylinders" in:
    // Add RGB cylinders in different positions
    renderer.addCylinderInstance(
      Vector[3](-1.0f, 0.2f, 0.0f),
      Vector[3](-0.3f, 0.2f, 0.0f),
      0.1f,
      Material(OPAQUE_RED, ior = 1.0f)
    )
    renderer.addCylinderInstance(
      Vector[3](-0.35f, 0.0f, 0.0f),
      Vector[3](0.35f, 0.0f, 0.0f),
      0.1f,
      Material(OPAQUE_GREEN, ior = 1.0f)
    )
    renderer.addCylinderInstance(
      Vector[3](0.3f, -0.2f, 0.0f),
      Vector[3](1.0f, -0.2f, 0.0f),
      0.1f,
      Material(OPAQUE_BLUE, ior = 1.0f)
    )

    val imageData = renderImage(TEST_IMAGE_SIZE)

    // Image should have color variation
    val ratio = ImageValidation.colorChannelRatio(imageData, TEST_IMAGE_SIZE)
    // All three channels should have some presence
    ratio.r should be > 0.1
    ratio.g should be > 0.1
    ratio.b should be > 0.1

  // ========== Cylinder with Plane ==========

  "Cylinder with ground plane" should "render shadows when enabled" in:
    renderer.clearPlanes()
    renderer.addPlane(1, true, -0.5f)  // Y-axis plane at y = -0.5
    renderer.setShadows(true)

    renderer.addCylinderInstance(
      Vector[3](-0.5f, 0.0f, 0.0f),
      Vector[3](0.5f, 0.0f, 0.0f),
      0.15f,
      Material(OPAQUE_WHITE, ior = 1.0f)
    )

    val imageData = renderImage(TEST_IMAGE_SIZE)

    // Should have variation indicating shadows
    val stdDev = ImageValidation.brightnessStdDev(imageData, TEST_IMAGE_SIZE)
    stdDev should be > 10.0

  // ========== Cylinder receiving shadows ==========

  // Top-down view of a wide, flat cylinder (a plinth) with a sphere hovering above its top cap.
  // The light comes in at an angle, so the sphere's shadow falls on the cap's -x side, beside
  // the sphere rather than under it; the mirror-image spot on the +x side stays lit.
  private val PlinthRadius = 1.5f
  private val PlinthTop = 0.0f
  private val PlinthBottom = -0.2f
  private val HoverHeight = 1.0f
  private val HoverRadius = 0.3f
  private val CameraHeight = 4.0f
  private val CameraFov = 60.0f
  private val TopDownUp = Vector[3](0.0f, 0.0f, -1.0f)
  private val ObliqueLight = Vector[3](-1.0f, -1.0f, 0.0f)
  // The light moves the shadow sideways by |x / y| of its direction per unit of drop, so the
  // shadow centre lies 1.0 from the axis. The sampled spot (8 px, about 0.12 world units) stays
  // about 0.3 inside the shadow (half-width 0.42 along x) and about 0.48 outside the sphere's
  // silhouette on the cap (radius 0.40 seen from the camera), so the sphere never covers it.
  private val ShadowOffsetWorld =
    (HoverHeight - PlinthTop) * math.abs(ObliqueLight.x / ObliqueLight.y)
  private val SampleRadiusPixels = 8
  private val MaxShadowedToLitRatio = 0.7
  private val MaxUnshadowedAsymmetry = 0.05

  private def addPlinth(): Unit =
    val _ = renderer.addCylinderInstance(
      Vector[3](0.0f, PlinthBottom, 0.0f),
      Vector[3](0.0f, PlinthTop, 0.0f),
      PlinthRadius,
      Material(OPAQUE_WHITE, ior = 1.0f)
    )

  private def lookDownFrom(height: Float): Unit =
    renderer.setCamera(
      Vector[3](0.0f, height, 0.0f), Vector[3](0.0f, PlinthTop, 0.0f), TopDownUp, CameraFov
    )

  /** Brightness of the cap at world (x, 0, 0) in a top-down image taken from `cameraHeight`.
    * The image is mirrored horizontally, so world -x is on its right. */
  private def capBrightness(image: Array[Byte], cameraHeight: Float, x: Float, radius: Int) =
    val halfWidthWorld = (cameraHeight - PlinthTop) * math.tan(math.toRadians(CameraFov / 2.0))
    val halfWidthPixels = TEST_IMAGE_SIZE.width / 2
    val column = halfWidthPixels - (x / halfWidthWorld * halfWidthPixels).toInt
    TestUtilities.regionBrightness(
      image, TEST_IMAGE_SIZE,
      TestUtilities.Region.centered(column, TEST_IMAGE_SIZE.height / 2, radius)
    )

  /** (shadow side, lit side) of the cap. */
  private def renderPlinthTopDown(shadows: Boolean): (Double, Double) =
    renderer.setShadows(shadows)
    lookDownFrom(CameraHeight)
    renderer.setLight(ObliqueLight, 1.0f)
    addPlinth()
    renderer.addSphereInstance(
      sphereTransform(0.0f, HoverHeight, 0.0f, HoverRadius),
      Material(OPAQUE_WHITE, ior = 1.0f)
    )
    val image = renderImage(TEST_IMAGE_SIZE)
    (
      capBrightness(image, CameraHeight, -ShadowOffsetWorld, SampleRadiusPixels),
      capBrightness(image, CameraHeight, ShadowOffsetWorld, SampleRadiusPixels)
    )

  "A cylinder's top cap" should "receive the shadow of a sphere above it" in:
    val (shadowSide, litSide) = renderPlinthTopDown(shadows = true)
    shadowSide should be < MaxShadowedToLitRatio * litSide

  it should "be evenly lit when shadows are off" in:
    val (shadowSide, litSide) = renderPlinthTopDown(shadows = false)
    math.abs(shadowSide - litSide) should be < MaxUnshadowedAsymmetry * litSide

  // The cap seen through a glass sphere: the camera ray refracts in and out of the sphere, so it
  // reaches the cylinder at bounce depth 2, and the cylinder traces its shadow ray from there --
  // the case the removed skip_shadows guard was said to protect. An opaque sphere off to the
  // side, lit from that side, shadows the cap right under the glass sphere, which is what the
  // centre of the image sees through it.
  private val LensCameraHeight = 5.0f
  private val LensHeight = 2.0f
  private val LensRadius = 0.7f
  private val CasterOffset = 1.5f  // the caster sits at (CasterOffset, CasterOffset, 0)
  private val CasterRadius = 0.3f
  // Carries the caster's shadow from (CasterOffset, CasterOffset, 0) to the cap's centre.
  private val SideLight = Vector[3](-1.0f, -1.0f, 0.0f)
  private val LensSampleRadiusPixels = 6
  private val MaxShadowedThroughLensRatio = 0.8

  private def renderCapThroughLens(shadows: Boolean): Double =
    renderer.clearAllInstances()
    renderer.setShadows(shadows)
    lookDownFrom(LensCameraHeight)
    renderer.setLight(SideLight, 1.0f)
    addPlinth()
    renderer.addSphereInstance(
      sphereTransform(0.0f, LensHeight, 0.0f, LensRadius), Material.Glass
    )
    renderer.addSphereInstance(
      sphereTransform(CasterOffset, CasterOffset, 0.0f, CasterRadius),
      Material(OPAQUE_WHITE, ior = 1.0f)
    )
    capBrightness(renderImage(TEST_IMAGE_SIZE), LensCameraHeight, 0.0f, LensSampleRadiusPixels)

  it should "receive a shadow where it is reached by a refracted ray" in:
    val lit = renderCapThroughLens(shadows = false)
    renderCapThroughLens(shadows = true) should be < MaxShadowedThroughLensRatio * lit

  // ========== Edge Cases ==========

  "Cylinder edge cases" should "handle very short cylinders" in:
    val result = renderer.addCylinderInstance(
      Vector[3](0.0f, 0.0f, 0.0f),
      Vector[3](0.01f, 0.0f, 0.0f),  // Very short
      0.1f,
      Material.Chrome
    )
    result.value should be >= 0

  it should "handle very thin cylinders" in:
    val result = renderer.addCylinderInstance(
      Vector[3](-1.0f, 0.0f, 0.0f),
      Vector[3](1.0f, 0.0f, 0.0f),
      0.001f,  // Very thin
      Material.Chrome
    )
    result.value should be >= 0

  it should "handle cylinders at various distances from camera" in:
    // Close cylinder
    val close = renderer.addCylinderInstance(
      Vector[3](-0.3f, 0.0f, 2.0f),
      Vector[3](0.3f, 0.0f, 2.0f),
      0.1f,
      Material.Chrome
    )
    close.value should be >= 0

    // Far cylinder
    val far = renderer.addCylinderInstance(
      Vector[3](-0.3f, 0.0f, -5.0f),
      Vector[3](0.3f, 0.0f, -5.0f),
      0.1f,
      Material.Chrome
    )
    far.value should be >= 0
