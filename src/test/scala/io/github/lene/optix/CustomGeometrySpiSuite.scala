package io.github.lene.optix

import com.typesafe.scalalogging.LazyLogging
import io.github.lene.optix.ThresholdConstants.TEST_IMAGE_SIZE
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Custom-geometry SPI (Sprint 35 Task 1.1d) — end-to-end regression for the
  * managed-pipeline registration path (the permanent successor to the discarded
  * PoC in SPRINT35_SPIKE_SPI.md).
  *
  * Uses a dedicated, params-independent stub primitive (`spi_test_stub.ptx`:
  * `__intersection__spi_stub` + a magenta `__closesthit__spi_stub`) as the
  * "external" module, so the test exercises only the SPI plumbing — module load
  * in the renderer's context, dynamic program group, a runtime geometry-type id
  * (>= the built-in count), the dynamically sized SBT block, and sbtOffset
  * dispatch — not any launch-params / material machinery. A magenta shape where
  * the instance sits proves dispatch reached the registered shader. When
  * menger-geometry supplies real 4D shaders (Task 1.2) it travels this exact path.
  */
class CustomGeometrySpiSuite extends AnyFlatSpec
    with Matchers
    with LazyLogging
    with RendererFixture:

  OptiXRenderer.isLibraryLoaded shouldBe true

  // First runtime id equals the built-in geometry-type count (GEOMETRY_TYPE_COUNT
  // in OptiXData.h). Kept here as the contract the SPI must honour.
  private val FirstRuntimeTypeId = 9

  private def loadStubPtx(): Array[Byte] =
    val resource = "/native/x86_64-linux/spi_test_stub.ptx"
    Option(getClass.getResourceAsStream(resource)) match
      case Some(stream) => try stream.readAllBytes() finally stream.close()
      case None =>
        java.nio.file.Files.readAllBytes(
          java.nio.file.Paths.get("target/native/x86_64-linux/bin/spi_test_stub.ptx"))

  private val identityTransform: Array[Float] =
    Array(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f)

  private def registerStub(): Int =
    renderer.registerCustomGeometry(
      loadStubPtx(), "__intersection__spi_stub", "__closesthit__spi_stub")

  private def countColor(image: Array[Byte], pred: (Int, Int, Int) => Boolean): Int =
    (0 until (TEST_IMAGE_SIZE.width * TEST_IMAGE_SIZE.height)).count { idx =>
      val px = ImageValidation.getRGB(image, idx)
      pred(px.r, px.g, px.b)
    }

  // The stub's closest hit writes a fixed magenta (R=230, G=40, B=200) unless per-
  // instance data overrides it.
  private def magentaPixelCount(image: Array[Byte]): Int =
    countColor(image, (r, g, b) => r > 150 && b > 150 && g < 100)

  "The custom-geometry SPI" should "allocate a runtime type id past the built-ins" in:
    registerStub() shouldBe FirstRuntimeTypeId

  it should "render an instance of a registered external primitive" in:
    val typeId = registerStub()
    // Stub is an object-space sphere of radius 0.5; the AABB must enclose it.
    val instanceId = renderer.addCustomGeometryInstance(
      typeId,
      Array(-0.6f, -0.6f, -0.6f),
      Array(0.6f, 0.6f, 0.6f),
      identityTransform)
    instanceId should be >= 0

    val image = renderImage(TEST_IMAGE_SIZE)
    val magenta = magentaPixelCount(image)
    logger.info(s"custom-geometry SPI: registered type $typeId, instance $instanceId, magenta=$magenta px")
    // The stub's magenta must appear: the instance dispatched through
    // sbtOffset = typeId*STRIDE to the registered SBT block and ran its IS/CH.
    magenta should be > 100

  it should "pass per-instance data to a registered primitive's shader (Task 1.1c)" in:
    val typeId = registerStub()
    val aabbMin = Array(-0.6f, -0.6f, -0.6f)
    val aabbMax = Array(0.6f, 0.6f, 0.6f)
    // 3x4 row-major transform translating along X so the two instances don't overlap.
    def xform(x: Float): Array[Float] = Array(1f, 0f, 0f, x, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f)
    // Per-instance colour blobs (R,G,B bytes) read back by __closesthit__spi_stub.
    val green = Array[Byte](0, 255.toByte, 0)
    val red   = Array[Byte](255.toByte, 0, 0)

    renderer.addCustomGeometryInstance(typeId, aabbMin, aabbMax, xform(-0.9f), green) should be >= 0
    renderer.addCustomGeometryInstance(typeId, aabbMin, aabbMax, xform(0.9f), red) should be >= 0

    val image = renderImage(TEST_IMAGE_SIZE)
    val greenPixels = countColor(image, (r, g, b) => g > 150 && r < 100 && b < 100)
    val redPixels = countColor(image, (r, g, b) => r > 150 && g < 100 && b < 100)
    logger.info(s"per-instance data: green=$greenPixels red=$redPixels px")
    // Each instance must render its OWN colour — proves per-instance custom data
    // reached the registrant's shader (indexed by geometry_data_index).
    greenPixels should be > 100
    redPixels should be > 100
