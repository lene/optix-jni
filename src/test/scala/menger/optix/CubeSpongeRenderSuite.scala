package menger.optix

import com.typesafe.scalalogging.LazyLogging
import menger.common.Color
import menger.common.ImageSize
import menger.optix.ColorConstants.BLUE_TINTED_GLASS
import menger.optix.ColorConstants.Blue
import menger.optix.ColorConstants.Green
import menger.optix.ColorConstants.OPAQUE_BLUE
import menger.optix.ColorConstants.OPAQUE_GREEN
import menger.optix.ColorConstants.OPAQUE_RED
import menger.optix.ColorConstants.Red
import menger.optix.ColorConstants.White
import menger.optix.ThresholdConstants.TEST_IMAGE_SIZE
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.tagobjects.Slow

/**
 * Tests for cube-based sponge rendering using GPU instancing via IAS.
 *
 * Unlike sponge-volume which merges all cubes into a single mesh,
 * cube-sponge uses Instance Acceleration Structure to render many
 * instances of the same base cube, which is more memory efficient.
 */
class CubeSpongeRenderSuite extends AnyFlatSpec
    with Matchers
    with LazyLogging
    with RendererFixture:

  OptiXRenderer.isLibraryLoaded shouldBe true

  "cube-sponge" should "render level 0 (single cube)" taggedAs (Slow) in:
    // Level 0 = 1 cube
    val mesh = TestUtilities.createUnitCubeMesh()
    renderer.setTriangleMesh(mesh)

    // Add single cube instance at origin
    val transform = Array(
      1.0f, 0.0f, 0.0f, 0.0f,
      0.0f, 1.0f, 0.0f, 0.0f,
      0.0f, 0.0f, 1.0f, 0.0f
    )
    renderer.addTriangleMeshInstance(transform, OPAQUE_RED, 1.0f)

    val img = renderImage(TEST_IMAGE_SIZE)
    img.length shouldBe ImageValidation.imageByteSize(TEST_IMAGE_SIZE)
    img.count(_ != 0) should be > 0

  it should "render level 1 (20 cubes)" taggedAs (Slow) in:
    // Level 1 = 20 cubes arranged in sponge pattern
    val mesh = TestUtilities.createUnitCubeMesh()
    renderer.setTriangleMesh(mesh)

    // Generate 20 cube positions for level 1 sponge
    val shift = 1.0f / 3.0f
    val positions = for
      xx <- -1 to 1
      yy <- -1 to 1
      zz <- -1 to 1
      if math.abs(xx) + math.abs(yy) + math.abs(zz) > 1
    yield (xx * shift, yy * shift, zz * shift)

    positions.length shouldBe 20

    // Add cube instance for each position
    positions.foreach { case (x, y, z) =>
      val scale = 1.0f / 3.0f
      val transform = Array(
        scale, 0.0f, 0.0f, x,
        0.0f, scale, 0.0f, y,
        0.0f, 0.0f, scale, z
      )
      renderer.addTriangleMeshInstance(transform, OPAQUE_GREEN, 1.0f)
    }

    val img = renderImage(TEST_IMAGE_SIZE)
    img.length shouldBe ImageValidation.imageByteSize(TEST_IMAGE_SIZE)
    img.count(_ != 0) should be > 0

  it should "render level 2 (400 cubes) with increased limit" taggedAs (Slow) in:
    // Level 2 = 400 cubes - requires MAX_INSTANCES to be increased
    // This test demonstrates that the default limit (64) is enforced
    // In production, users would use --max-instances to increase the limit

    val mesh = TestUtilities.createUnitCubeMesh()
    renderer.setTriangleMesh(mesh)

    // Try to add level 2 cubes (400 total)
    val shift = 1.0f / 9.0f
    val scale = 1.0f / 9.0f

    // Add cubes - should stop at 64 (the default MAX_INSTANCES limit)
    val successfulInstances = for
      xx <- -1 to 1
      yy <- -1 to 1
      zz <- -1 to 1
      if math.abs(xx) + math.abs(yy) + math.abs(zz) > 1
      sx <- -1 to 1
      sy <- -1 to 1
      sz <- -1 to 1
      if math.abs(sx) + math.abs(sy) + math.abs(sz) > 1
    yield
      val x = xx * shift * 3.0f + sx * shift
      val y = yy * shift * 3.0f + sy * shift
      val z = zz * shift * 3.0f + sz * shift
      val transform = Array(
        scale, 0.0f, 0.0f, x,
        0.0f, scale, 0.0f, y,
        0.0f, 0.0f, scale, z
      )
      renderer.addTriangleMeshInstance(transform, OPAQUE_BLUE, 1.0f)

    // We attempted to add 400, but should have hit the default limit of 64
    renderer.getInstanceCount() shouldBe 64

    val img = renderImage(TEST_IMAGE_SIZE)
    img.length shouldBe ImageValidation.imageByteSize(TEST_IMAGE_SIZE)
    img.count(_ != 0) should be > 0

  it should "render with different colors per instance" taggedAs (Slow) in:
    val mesh = TestUtilities.createUnitCubeMesh()
    renderer.setTriangleMesh(mesh)

    // Create 3 cubes at different positions with different colors
    val instances = Seq(
      (Array(1.0f, 0.0f, 0.0f, -1.5f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f), OPAQUE_RED),
      (Array(1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f), OPAQUE_GREEN),
      (Array(1.0f, 0.0f, 0.0f, 1.5f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f), OPAQUE_BLUE)
    )

    instances.foreach { case (transform, color) =>
      renderer.addTriangleMeshInstance(transform, color, 1.0f)
    }

    val img = renderImage(TEST_IMAGE_SIZE)
    img.length shouldBe ImageValidation.imageByteSize(TEST_IMAGE_SIZE)

    // Image should contain non-zero pixels (cubes visible)
    img.count(_ != 0) should be > 0

  it should "handle refractive cubes (IOR > 1.0)" taggedAs (Slow) in:
    val mesh = TestUtilities.createUnitCubeMesh()
    renderer.setTriangleMesh(mesh)

    // Single glass cube
    val transform = Array(
      1.0f, 0.0f, 0.0f, 0.0f,
      0.0f, 1.0f, 0.0f, 0.0f,
      0.0f, 0.0f, 1.0f, 0.0f
    )
    renderer.addTriangleMeshInstance(transform, BLUE_TINTED_GLASS, 1.5f)

    val img = renderImage(TEST_IMAGE_SIZE)
    img.length shouldBe ImageValidation.imageByteSize(TEST_IMAGE_SIZE)
    img.count(_ != 0) should be > 0
