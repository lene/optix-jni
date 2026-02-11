package menger.optix
import com.typesafe.scalalogging.LazyLogging
import menger.common.Vector
import menger.optix.ColorConstants.OPAQUE_BLUE
import menger.optix.ColorConstants.OPAQUE_GREEN
import menger.optix.ColorConstants.OPAQUE_RED
import menger.optix.ColorConstants.OPAQUE_WHITE
import menger.optix.ImageValidation.detectSphereCenter
import menger.optix.ImageValidation.dominantColorChannel
import menger.optix.ImageValidation.spherePixelArea
import menger.optix.ThresholdConstants.LARGE_SPHERE_MIN_AREA
import menger.optix.ThresholdConstants.MEDIUM_SPHERE_MAX_AREA
import menger.optix.ThresholdConstants.MEDIUM_SPHERE_MIN_AREA
import menger.optix.ThresholdConstants.MIN_OFFSET_DETECTION
import menger.optix.ThresholdConstants.SMALL_SPHERE_MAX_AREA
import menger.optix.ThresholdConstants.TEST_IMAGE_SIZE
import menger.optix.ThresholdConstants.VERY_LARGE_SPHERE_MIN_AREA
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers


class OpaqueSphereSuite extends AnyFlatSpec
    with Matchers
    with LazyLogging
    with RendererFixture:

  // Ensure library is loaded before running tests
  OptiXRenderer.isLibraryLoaded shouldBe true

  "Opaque sphere" should "render at radius 0.1" in:
    // Setup: Use TestScenario for configuration
    TestScenario.smallSphere()
      .withSphereColor(OPAQUE_GREEN)
      .applyTo(renderer)

    // Execute
    val imageData = renderer.render(TEST_IMAGE_SIZE).get

    // Verify
    imageData shouldBe a[Array[Byte]]
    imageData.length shouldBe ImageValidation.imageByteSize(TEST_IMAGE_SIZE)

    val area = ImageValidation.spherePixelArea(
      imageData,
      TEST_IMAGE_SIZE
    )
    area should be < SMALL_SPHERE_MAX_AREA

  it should "render at radius 0.5" in:
    TestScenario.default()
      .applyTo(renderer)

    val imageData = renderer.render(TEST_IMAGE_SIZE).get
    val area = ImageValidation.spherePixelArea(
      imageData,
      TEST_IMAGE_SIZE
    )

    area should (be > MEDIUM_SPHERE_MIN_AREA and be < MEDIUM_SPHERE_MAX_AREA)

  it should "render at radius 1.0" in:
    TestScenario.largeSphere()
      .withSphereColor(OPAQUE_GREEN)
      .applyTo(renderer)

    val imageData = renderer.render(TEST_IMAGE_SIZE).get
    val area = ImageValidation.spherePixelArea(
      imageData,
      TEST_IMAGE_SIZE
    )

    area should be > LARGE_SPHERE_MIN_AREA

  it should "render at radius 2.0" in:
    TestScenario.veryLargeSphere()
      .withSphereColor(OPAQUE_GREEN)
      .applyTo(renderer)

    val imageData = renderer.render(TEST_IMAGE_SIZE).get
    val area = ImageValidation.spherePixelArea(
      imageData,
      TEST_IMAGE_SIZE
    )

    area should be > VERY_LARGE_SPHERE_MIN_AREA

  it should "render at center position (0, 0, 0)" in:
    TestScenario.default()
      .applyTo(renderer)

    val imageData = renderer.render(TEST_IMAGE_SIZE).get
    val center = ImageValidation.detectSphereCenter(
      imageData,
      TEST_IMAGE_SIZE
    )

    // Sphere at origin should appear near image center
    center.x should (be >= TEST_IMAGE_SIZE.width / 2 - MIN_OFFSET_DETECTION and
      be <= TEST_IMAGE_SIZE.width / 2 + MIN_OFFSET_DETECTION)
    center.y should (be >= TEST_IMAGE_SIZE.height / 2 - MIN_OFFSET_DETECTION and
      be <= TEST_IMAGE_SIZE.height / 2 + MIN_OFFSET_DETECTION)

  it should "render with offset position (1, 0, 0)" in:
    TestScenario.default()
      .withSpherePosition(Vector[3](1.0f, 0.0f, 0.0f))  // Offset to right
      .applyTo(renderer)

    val imageData = renderer.render(TEST_IMAGE_SIZE).get
    val center = ImageValidation.detectSphereCenter(
      imageData,
      TEST_IMAGE_SIZE
    )

    // Sphere offset to +X should be detected somewhere in image
    center.x should (be >= 0 and be < TEST_IMAGE_SIZE.width)

  it should "render in pure red" in:
    TestScenario.default()
      .withSphereColor(OPAQUE_RED)
      .applyTo(renderer)

    val imageData = renderer.render(TEST_IMAGE_SIZE).get
    val dominantChannel = ImageValidation.dominantColorChannel(
      imageData,
      TEST_IMAGE_SIZE.width,
      TEST_IMAGE_SIZE.height
    )

    dominantChannel shouldBe "r"

  it should "render in pure green" in:
    TestScenario.default()
      .withSphereColor(OPAQUE_GREEN)
      .applyTo(renderer)

    val imageData = renderer.render(TEST_IMAGE_SIZE).get
    val dominantChannel = ImageValidation.dominantColorChannel(
      imageData,
      TEST_IMAGE_SIZE.width,
      TEST_IMAGE_SIZE.height
    )

    dominantChannel shouldBe "g"

  it should "render in pure blue" in:
    TestScenario.default()
      .withSphereColor(OPAQUE_BLUE)
      .applyTo(renderer)

    val imageData = renderer.render(TEST_IMAGE_SIZE).get
    val dominantChannel = ImageValidation.dominantColorChannel(
      imageData,
      TEST_IMAGE_SIZE.width,
      TEST_IMAGE_SIZE.height
    )

    dominantChannel shouldBe "b"

  it should "render in white/grayscale" in:
    TestScenario.default()
      .withSphereColor(OPAQUE_WHITE)
      .applyTo(renderer)

    val imageData = renderer.render(TEST_IMAGE_SIZE).get
    val dominantChannel = ImageValidation.dominantColorChannel(
      imageData,
      TEST_IMAGE_SIZE.width,
      TEST_IMAGE_SIZE.height
    )

    dominantChannel shouldBe "gray"
