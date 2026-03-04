package menger.optix

import menger.common.Color
import menger.common.ImageSize
import menger.common.Vector
import menger.optix.ColorConstants.OPAQUE_BLUE
import menger.optix.ColorConstants.OPAQUE_GREEN
import menger.optix.ColorConstants.OPAQUE_RED
import menger.optix.ColorConstants.OPAQUE_WHITE
import menger.optix.ThresholdConstants.QUICK_TEST_SIZE
import menger.optix.ThresholdConstants.TEST_IMAGE_SIZE
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
    result shouldBe defined
    result.get should be >= 0

  it should "return a valid instance ID with Material parameter" in:
    val p0 = Vector[3](0.0f, -0.5f, 0.0f)
    val p1 = Vector[3](0.0f, 0.5f, 0.0f)
    val result = renderer.addCylinderInstance(p0, p1, 0.1f, Material.Glass)
    result shouldBe defined
    result.get should be >= 0

  it should "return a valid instance ID with color and IOR parameters" in:
    val p0 = Vector[3](0.0f, 0.0f, -0.5f)
    val p1 = Vector[3](0.0f, 0.0f, 0.5f)
    val result = renderer.addCylinderInstance(p0, p1, 0.05f, OPAQUE_GREEN, 1.0f)
    result shouldBe defined
    result.get should be >= 0

  it should "support different cylinder radii" in:
    val p0 = Vector[3](0.0f, 0.0f, 0.0f)
    val p1 = Vector[3](1.0f, 0.0f, 0.0f)

    // Thin cylinder
    val thin = renderer.addCylinderInstance(p0, p1, 0.01f, Material.Chrome)
    thin shouldBe defined

    // Thick cylinder
    val thick = renderer.addCylinderInstance(p0, p1, 0.5f, Material.Chrome)
    thick shouldBe defined

  it should "support various orientations" in:
    // X-aligned
    val xAligned = renderer.addCylinderInstance(
      Vector[3](-1.0f, 0.0f, 0.0f),
      Vector[3](1.0f, 0.0f, 0.0f),
      0.1f, Material.Chrome
    )
    xAligned shouldBe defined

    // Y-aligned (vertical)
    val yAligned = renderer.addCylinderInstance(
      Vector[3](0.0f, -1.0f, 0.0f),
      Vector[3](0.0f, 1.0f, 0.0f),
      0.1f, Material.Chrome
    )
    yAligned shouldBe defined

    // Z-aligned
    val zAligned = renderer.addCylinderInstance(
      Vector[3](0.0f, 0.0f, -1.0f),
      Vector[3](0.0f, 0.0f, 1.0f),
      0.1f, Material.Chrome
    )
    zAligned shouldBe defined

    // Diagonal
    val diagonal = renderer.addCylinderInstance(
      Vector[3](-1.0f, -1.0f, -1.0f),
      Vector[3](1.0f, 1.0f, 1.0f),
      0.1f, Material.Chrome
    )
    diagonal shouldBe defined

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

    ids.foreach(_ shouldBe defined)
    ids.map(_.get).distinct.size shouldBe 5  // All unique IDs

  it should "work with mixed geometry (spheres and cylinders)" in:
    // Add a sphere first
    val sphereId = renderer.addSphereInstance(
      Vector[3](0.0f, 0.0f, 0.0f),
      Material.Glass
    )
    sphereId shouldBe defined

    // Then add cylinders
    val cylinder1 = renderer.addCylinderInstance(
      Vector[3](-1.0f, 0.0f, 0.0f),
      Vector[3](-0.5f, 0.0f, 0.0f),
      0.05f,
      Material.Chrome
    )
    cylinder1 shouldBe defined

    val cylinder2 = renderer.addCylinderInstance(
      Vector[3](0.5f, 0.0f, 0.0f),
      Vector[3](1.0f, 0.0f, 0.0f),
      0.05f,
      Material.Chrome
    )
    cylinder2 shouldBe defined

  // ========== Cylinder Materials ==========

  "Cylinder materials" should "support film preset (translucent)" in:
    val result = renderer.addCylinderInstance(
      Vector[3](-0.5f, 0.0f, 0.0f),
      Vector[3](0.5f, 0.0f, 0.0f),
      0.1f,
      Material.Film
    )
    result shouldBe defined

  it should "support parchment preset (semi-translucent)" in:
    val result = renderer.addCylinderInstance(
      Vector[3](-0.5f, 0.0f, 0.0f),
      Vector[3](0.5f, 0.0f, 0.0f),
      0.1f,
      Material.Parchment
    )
    result shouldBe defined

  it should "support emissive materials" in:
    val emissiveMaterial = Material.Film.copy(emission = 5.0f)
    val result = renderer.addCylinderInstance(
      Vector[3](-0.5f, 0.0f, 0.0f),
      Vector[3](0.5f, 0.0f, 0.0f),
      0.1f,
      emissiveMaterial
    )
    result shouldBe defined

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
    result shouldBe defined

  // ========== Cylinder Rendering ==========

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

  // ========== Edge Cases ==========

  "Cylinder edge cases" should "handle very short cylinders" in:
    val result = renderer.addCylinderInstance(
      Vector[3](0.0f, 0.0f, 0.0f),
      Vector[3](0.01f, 0.0f, 0.0f),  // Very short
      0.1f,
      Material.Chrome
    )
    result shouldBe defined

  it should "handle very thin cylinders" in:
    val result = renderer.addCylinderInstance(
      Vector[3](-1.0f, 0.0f, 0.0f),
      Vector[3](1.0f, 0.0f, 0.0f),
      0.001f,  // Very thin
      Material.Chrome
    )
    result shouldBe defined

  it should "handle cylinders at various distances from camera" in:
    // Close cylinder
    val close = renderer.addCylinderInstance(
      Vector[3](-0.3f, 0.0f, 2.0f),
      Vector[3](0.3f, 0.0f, 2.0f),
      0.1f,
      Material.Chrome
    )
    close shouldBe defined

    // Far cylinder
    val far = renderer.addCylinderInstance(
      Vector[3](-0.3f, 0.0f, -5.0f),
      Vector[3](0.3f, 0.0f, -5.0f),
      0.1f,
      Material.Chrome
    )
    far shouldBe defined
