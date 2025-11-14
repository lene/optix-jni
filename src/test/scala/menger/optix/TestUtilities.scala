package menger.optix

import com.typesafe.scalalogging.LazyLogging
import java.io.{File, FileOutputStream}
import scala.util.{Try, Using}

/**
 * Consolidated test utilities for OptiX JNI tests.
 *
 * Provides shared helper functions previously duplicated across test files:
 * - Image file I/O (savePPM)
 * - Common assertions and validations
 * - Test data generation
 *
 * Uses functional programming style (Try, Using) instead of try/catch.
 */
object TestUtilities extends LazyLogging:

  /**
   * Save image data as PPM (Portable Pixel Map) format.
   *
   * PPM is a simple uncompressed image format that's easy to generate.
   * Can be viewed with GIMP, ImageMagick, or converted with:
   * `convert output.ppm output.png`
   *
   * Functional implementation using scala.util.Using for automatic resource management.
   *
   */
  def savePPM(
    filename: String,
    data: Array[Byte],
    width: Int,
    height: Int
  ): Try[File] =
    val file = new File(filename)

    Using(new FileOutputStream(file)) { out =>
      // PPM header
      val header = s"P6\n$width $height\n255\n"
      out.write(header.getBytes("ASCII"))

      // Write RGB data (skip alpha channel)
      // Functional style using foreach instead of imperative for loop
      (0 until width * height).foreach { i =>
        val offset = i * 4
        // Convert signed bytes to unsigned
        out.write(data(offset) & 0xFF)     // R
        out.write(data(offset + 1) & 0xFF) // G
        out.write(data(offset + 2) & 0xFF) // B
        // Skip alpha (data(offset + 3))
      }

      file
    }

  /**
   * Save image data as PNG format using external ImageIO.
   *
   */
  def savePNG(
    filename: String,
    data: Array[Byte],
    width: Int,
    height: Int
  ): Try[File] =
    import javax.imageio.ImageIO
    import java.awt.image.BufferedImage

    Try {
      val image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)

      // Convert RGBA byte array to BufferedImage
      (0 until width * height).foreach { i =>
        val offset = i * 4
        val r = data(offset) & 0xFF
        val g = data(offset + 1) & 0xFF
        val b = data(offset + 2) & 0xFF
        val a = data(offset + 3) & 0xFF

        val argb = (a << 24) | (r << 16) | (g << 8) | b
        val x = i % width
        val y = i / width
        image.setRGB(x, y, argb)
      }

      val file = new File(filename)
      ImageIO.write(image, "PNG", file)
      file
    }

  /**
   * Create a test renderer with standard configuration.
   *
   */
  def createTestRenderer(scenario: TestScenario): Try[OptiXRenderer] =
    for
      renderer <- Try(new OptiXRenderer())
      _        <- Try(renderer.initialize())
      _         = scenario.applyTo(renderer)
    yield renderer

  /**
   * Run a test with automatic renderer cleanup.
   *
   * Functional pattern for renderer lifecycle management.
   * Ensures dispose() is called even if test fails.
   *
   */
  def withRenderer[A](scenario: TestScenario)(test: OptiXRenderer => A): Try[A] =
    Try {
      val renderer = new OptiXRenderer()
      try
        renderer.initialize()
        scenario.applyTo(renderer)
        test(renderer)
      finally
        renderer.dispose()
    }

  /**
   * Measure rendering performance in frames per second.
   *
   * Renders the specified number of frames and calculates average FPS.
   * Uses functional foreach instead of imperative loops.
   *
   */
  def measureFPS(
    renderer: OptiXRenderer,
    scenario: TestScenario,
    width: Int,
    height: Int,
    frames: Int = 100
  ): Double =
    scenario.applyTo(renderer)

    val start = System.nanoTime
    (0 until frames).foreach { _ =>
      renderer.render(width, height).get
    }
    val duration = (System.nanoTime - start) / 1e9
    frames / duration
