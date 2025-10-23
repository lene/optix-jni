package menger.optix

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.util.Try
import java.io.{File, FileOutputStream}

/**
 * Test suite for OptiX JNI bindings.
 *
 * Phase 1: Tests basic structure - library loading, method calls
 * Phase 2+: Will add tests for actual rendering functionality
 */
object OptiXRendererTest {
  // Test configuration constants
  object TestConfig {
    val SmallImageSize = (10, 10)
    val StandardImageSize = (800, 600)

    // Default sphere configuration
    val DefaultSphere = (0.0f, 0.0f, 0.0f, 1.5f)

    // Default camera configuration
    val DefaultCameraEye = Array(0.0f, 0.0f, 3.0f)
    val DefaultCameraLookAt = Array(0.0f, 0.0f, 0.0f)
    val DefaultCameraUp = Array(0.0f, 1.0f, 0.0f)
    val DefaultCameraFov = 60.0f

    // Default light configuration
    val DefaultLightDirection = Array(0.5f, 0.5f, -0.5f)
    val DefaultLightIntensity = 1.0f
  }

  // Helper for saving PPM images
  object ImageIO {
    def savePPM(imageData: Array[Byte], width: Int, height: Int, filename: String): File = {
      val outputFile = new File(filename)
      val header = s"P6\n$width $height\n255\n"

      val out = new FileOutputStream(outputFile)
      try {
        out.write(header.getBytes("ASCII"))

        for (i <- 0 until width * height) {
          val offset = i * 4
          out.write(imageData(offset) & 0xFF)     // R
          out.write(imageData(offset + 1) & 0xFF) // G
          out.write(imageData(offset + 2) & 0xFF) // B
        }
      } finally {
        out.close()
      }

      outputFile
    }
  }
}

class OptiXRendererTest extends AnyFlatSpec with Matchers {
  import OptiXRendererTest._

  // Ensure library is loaded before running tests
  OptiXRenderer.isLibraryLoaded shouldBe true

  "OptiXRenderer" should "be instantiable" in new OptiXRenderer {
    this should not be null
  }

  it should "load native library without error" in {
    // The library should already be loaded by the companion object
    // If we got this far without UnsatisfiedLinkError, it worked
    noException should be thrownBy { new OptiXRenderer() }
  }

  it should "allow initialize() to be called without crashing" in new OptiXRenderer {
    noException should be thrownBy { initialize() }
  }

  it should "return true from initialize() (placeholder implementation)" in new OptiXRenderer {
    val initialized = initialize()
    initialized shouldBe true
  }

  it should "allow setSphere() to be called without crashing" in new OptiXRenderer {
    initialize()
    noException should be thrownBy { setSphere(0.0f, 0.0f, 0.0f, 1.5f) }
  }

  it should "allow setCamera() to be called without crashing" in new OptiXRenderer {
    initialize()
    val eye = Array(0.0f, 0.0f, 3.0f)
    val lookAt = Array(0.0f, 0.0f, 0.0f)
    val up = Array(0.0f, 1.0f, 0.0f)
    noException should be thrownBy { setCamera(eye, lookAt, up, 60.0f) }
  }

  it should "allow setLight() to be called without crashing" in new OptiXRenderer {
    initialize()
    val direction = Array(0.5f, 0.5f, -0.5f)
    noException should be thrownBy { setLight(direction, 1.0f) }
  }

  it should "return non-null array from render()" in new OptiXRenderer {
    initialize()
    val (width, height) = TestConfig.StandardImageSize
    val result = render(width, height)
    result should not be null
  }

  it should "return correct size array from render() (RGBA)" in new OptiXRenderer {
    initialize()
    val (width, height) = TestConfig.StandardImageSize
    val result = render(width, height)
    val expectedSize = width * height * 4 // RGBA
    result.length shouldBe expectedSize
  }

  it should "render actual OptiX output (not placeholder)" in new OptiXRenderer {
    initialize()
    val (width, height) = TestConfig.SmallImageSize
    val result = render(width, height)
    // Check first pixel - should contain actual rendered output
    // The exact values depend on sphere rendering, but shouldn't be all zeros or all 255
    result should not be null
    result.length shouldBe width * height * 4
    result(3) shouldBe -1  // A = 255 (alpha should always be 255)

    // First pixel should have some rendered content (background or sphere)
    // Not checking exact values as they depend on camera/lighting setup
    val hasNonZeroPixel = result.take(width * height * 4).exists(b => b != 0 && b != -1)
    hasNonZeroPixel shouldBe true
  }

  it should "allow dispose() to be called without crashing" in new OptiXRenderer {
    initialize()
    noException should be thrownBy { dispose() }
  }

  it should "allow dispose() to be called multiple times safely" in new OptiXRenderer {
    initialize()
    dispose()
    noException should be thrownBy { dispose() }
  }

  it should "return a boolean from isAvailable" in new OptiXRenderer {
    val available = isAvailable
    // We can't assert true/false here as it depends on the system
    // But we verify it returns a valid boolean
    available shouldBe a[Boolean]
  }

  it should "support full workflow: init -> configure -> render -> dispose" in new OptiXRenderer {
    val initialized = initialize()
    initialized shouldBe true

    val (x, y, z, r) = TestConfig.DefaultSphere
    setSphere(x, y, z, r)
    setCamera(TestConfig.DefaultCameraEye, TestConfig.DefaultCameraLookAt,
              TestConfig.DefaultCameraUp, TestConfig.DefaultCameraFov)
    setLight(TestConfig.DefaultLightDirection, TestConfig.DefaultLightIntensity)

    val image = render(100, 100)
    image.length shouldBe 100 * 100 * 4

    dispose()
  }

  it should "save rendered output as PPM for visual inspection" in new OptiXRenderer {
    val (width, height) = TestConfig.StandardImageSize
    val filename = "optix_test_output.ppm"

    // Remove any pre-existing output file to ensure test actually creates it
    val outputFile = new File(filename)
    if (outputFile.exists()) {
      outputFile.delete()
    }

    val initialized = initialize()
    initialized shouldBe true

    val (x, y, z, r) = TestConfig.DefaultSphere
    setSphere(x, y, z, r)
    setCamera(TestConfig.DefaultCameraEye, TestConfig.DefaultCameraLookAt,
              TestConfig.DefaultCameraUp, TestConfig.DefaultCameraFov)
    setLight(TestConfig.DefaultLightDirection, TestConfig.DefaultLightIntensity)

    val imageData = render(width, height)
    imageData.length shouldBe width * height * 4

    val savedFile = ImageIO.savePPM(imageData, width, height, filename)

    println(s"\n=== Rendered image saved to: ${savedFile.getAbsolutePath}")
    println(s"=== Size: ${width}x${height} (${savedFile.length()} bytes)")
    println("=== View with: gimp optix_test_output.ppm")
    println("=== Or convert: convert optix_test_output.ppm optix_test_output.png\n")

    savedFile.exists() shouldBe true
    val expectedSize = s"P6\n$width $height\n255\n".length + width * height * 3
    savedFile.length() shouldBe expectedSize

    dispose()
  }
}
