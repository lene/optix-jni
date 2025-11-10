package menger.optix

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import com.typesafe.scalalogging.LazyLogging

class WindowResizeDiameterTest extends AnyFlatSpec with Matchers with LazyLogging {

  /**
   * Measure the vertical diameter of the sphere by scanning the center column.
   * Returns the number of pixels where the sphere is visible (red > 50).
   */
  private def measureVerticalDiameter(img: Array[Byte], width: Int, height: Int): Int = {
    val centerX = width / 2
    var count = 0
    for (y <- 0 until height) {
      val idx = (y * width + centerX) * 4
      val r = img(idx) & 0xFF
      val g = img(idx + 1) & 0xFF
      val b = img(idx + 2) & 0xFF
      // Detect pure red sphere: red must be dominant and high
      if (r > 200 && r > g * 3 && r > b * 3) {
        count += 1
      }
    }
    count
  }

  /**
   * Measure the horizontal diameter of the sphere by scanning the center row.
   * Returns the number of pixels where the sphere is visible (red > 50).
   */
  private def measureHorizontalDiameter(img: Array[Byte], width: Int, height: Int): Int = {
    val centerY = height / 2
    var count = 0
    for (x <- 0 until width) {
      val idx = (centerY * width + x) * 4
      val r = img(idx) & 0xFF
      val g = img(idx + 1) & 0xFF
      val b = img(idx + 2) & 0xFF
      // Detect pure red sphere: red must be dominant and high
      if (r > 200 && r > g * 3 && r > b * 3) {
        count += 1
      }
    }
    count
  }

  "OptiXRenderer" should "maintain sphere vertical size when window width changes" in {
    assume(OptiXRenderer.isLibraryLoaded, "OptiX library not available")

    val renderer = new OptiXRenderer()
    renderer.initialize() shouldBe true

    try {
      // Set up a simple sphere at origin
      renderer.setSphere(0f, 0f, 0f, 1.0f)
      renderer.setSphereColor(1f, 0f, 0f, 1f)

      val eye = Array(0f, 0f, 5f)
      val lookAt = Array(0f, 0f, 0f)
      val up = Array(0f, 1f, 0f)
      val fov = 45f

      // Render at 800x800 (square)
      renderer.updateImageDimensions(800, 800)
      renderer.setCamera(eye, lookAt, up, fov)
      val img1 = renderer.render(800, 800)
      TestUtilities.savePPM("window_resize_diameter_800x800.ppm", img1, 800, 800)
      val vDiam1 = measureVerticalDiameter(img1, 800, 800)
      val hDiam1 = measureHorizontalDiameter(img1, 800, 800)
      logger.info(s"800x800: vertical=${vDiam1}px, horizontal=${hDiam1}px (saved to window_resize_diameter_800x800.ppm)")

      // In square aspect, vertical and horizontal should be approximately equal
      Math.abs(vDiam1 - hDiam1) should be < 5

      // Render at 1600x800 (wide 2:1)
      renderer.updateImageDimensions(1600, 800)
      renderer.setCamera(eye, lookAt, up, fov)
      val img2 = renderer.render(1600, 800)
      TestUtilities.savePPM("window_resize_diameter_1600x800.ppm", img2, 1600, 800)
      val vDiam2 = measureVerticalDiameter(img2, 1600, 800)
      val hDiam2 = measureHorizontalDiameter(img2, 1600, 800)
      logger.info(s"1600x800: vertical=${vDiam2}px, horizontal=${hDiam2}px (saved to window_resize_diameter_1600x800.ppm)")

      // CRITICAL: Vertical diameter should stay the same!
      // When we make the window wider, we should see MORE on the sides,
      // but the vertical size of the sphere should not change
      Math.abs(vDiam2 - vDiam1) should be < 5

      // Horizontal diameter should also stay approximately the same
      // (sphere is round, so horizontal size should match vertical size)
      Math.abs(hDiam2 - hDiam1) should be < 5

      // Render at 800x1600 (tall 1:2)
      renderer.updateImageDimensions(800, 1600)
      renderer.setCamera(eye, lookAt, up, fov)
      val img3 = renderer.render(800, 1600)
      TestUtilities.savePPM("window_resize_diameter_800x1600.ppm", img3, 800, 1600)
      val vDiam3 = measureVerticalDiameter(img3, 800, 1600)
      val hDiam3 = measureHorizontalDiameter(img3, 800, 1600)
      logger.info(s"800x1600: vertical=${vDiam3}px, horizontal=${hDiam3}px (saved to window_resize_diameter_800x1600.ppm)")

      // When we make the window taller, horizontal diameter should stay the same
      Math.abs(hDiam3 - hDiam1) should be < 5

      // Vertical and horizontal should still match (sphere is round)
      Math.abs(vDiam3 - hDiam3) should be < 5

    } finally {
      renderer.dispose()
    }
  }
}
