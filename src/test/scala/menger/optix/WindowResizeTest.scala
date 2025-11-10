package menger.optix

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import com.typesafe.scalalogging.LazyLogging

class WindowResizeTest extends AnyFlatSpec with Matchers with LazyLogging {

  "OptiXRenderer" should "maintain correct aspect ratio when window is resized" in {
    assume(OptiXRenderer.isLibraryLoaded, "OptiX library not available")

    val renderer = new OptiXRenderer()
    renderer.initialize() shouldBe true

    try {
      // Set up a simple sphere
      renderer.setSphere(0f, 0f, 0f, 1.0f)
      renderer.setSphereColor(1f, 0f, 0f, 1f)

      // Initial camera setup - square aspect ratio (800x800)
      val eye = Array(0f, 0f, 5f)
      val lookAt = Array(0f, 0f, 0f)
      val up = Array(0f, 1f, 0f)
      val fov = 45f

      renderer.updateImageDimensions(800, 800)
      renderer.setCamera(eye, lookAt, up, fov)

      // Render at 800x800
      val img1 = renderer.render(800, 800)
      img1 should not be null
      img1.length shouldBe 800 * 800 * 4
      TestUtilities.savePPM("window_resize_800x800.ppm", img1, 800, 800)
      logger.info("Saved 800x800 image to window_resize_800x800.ppm")

      // Sample the center pixel - sphere should be visible
      val centerIdx1 = (400 * 800 + 400) * 4
      val centerR1 = img1(centerIdx1) & 0xFF
      centerR1 should be > 50 // Should have some red from the sphere

      // Now resize to wide aspect ratio (1600x800) - 2:1
      renderer.updateImageDimensions(1600, 800)
      renderer.setCamera(eye, lookAt, up, fov)

      // Render at 1600x800
      val img2 = renderer.render(1600, 800)
      img2 should not be null
      img2.length shouldBe 1600 * 800 * 4
      TestUtilities.savePPM("window_resize_1600x800.ppm", img2, 1600, 800)
      logger.info("Saved 1600x800 image to window_resize_1600x800.ppm")

      // Sample the center pixel - sphere should still be visible and similar brightness
      val centerIdx2 = (400 * 1600 + 800) * 4
      val centerR2 = img2(centerIdx2) & 0xFF

      // Center should still show the sphere with similar intensity
      // (allowing for some variation due to different sampling)
      centerR2 should be > 50
      Math.abs(centerR2 - centerR1) should be < 100

      // Now resize to tall aspect ratio (800x1600) - 1:2
      renderer.updateImageDimensions(800, 1600)
      renderer.setCamera(eye, lookAt, up, fov)

      // Render at 800x1600
      val img3 = renderer.render(800, 1600)
      img3 should not be null
      img3.length shouldBe 800 * 1600 * 4
      TestUtilities.savePPM("window_resize_800x1600.ppm", img3, 800, 1600)
      logger.info("Saved 800x1600 image to window_resize_800x1600.ppm")

      // Sample the center pixel
      val centerIdx3 = (800 * 800 + 400) * 4
      val centerR3 = img3(centerIdx3) & 0xFF

      // Center should still show the sphere
      centerR3 should be > 50
      Math.abs(centerR3 - centerR1) should be < 100

    } finally {
      renderer.dispose()
    }
  }

  it should "not move the look-at point when aspect ratio changes" in {
    assume(OptiXRenderer.isLibraryLoaded, "OptiX library not available")

    val renderer = new OptiXRenderer()
    renderer.initialize() shouldBe true

    try {
      // Set up a sphere off-center
      renderer.setSphere(1f, 0f, 0f, 0.5f)
      renderer.setSphereColor(0f, 1f, 0f, 1f) // Green

      // Camera looking at (1, 0, 0) where the sphere is
      val eye = Array(1f, 0f, 5f)
      val lookAt = Array(1f, 0f, 0f)
      val up = Array(0f, 1f, 0f)
      val fov = 45f

      // Render at square aspect ratio
      renderer.updateImageDimensions(800, 800)
      renderer.setCamera(eye, lookAt, up, fov)
      val img1 = renderer.render(800, 800)
      TestUtilities.savePPM("window_resize_lookat_800x800.ppm", img1, 800, 800)
      logger.info("Saved 800x800 look-at image to window_resize_lookat_800x800.ppm")

      // Center pixel should show the green sphere
      val centerIdx1 = (400 * 800 + 400) * 4
      val centerG1 = img1(centerIdx1 + 1) & 0xFF
      centerG1 should be > 50

      // Render at wide aspect ratio - sphere should still be centered vertically
      renderer.updateImageDimensions(1600, 800)
      renderer.setCamera(eye, lookAt, up, fov)
      val img2 = renderer.render(1600, 800)
      TestUtilities.savePPM("window_resize_lookat_1600x800.ppm", img2, 1600, 800)
      logger.info("Saved 1600x800 look-at image to window_resize_lookat_1600x800.ppm")

      // Center pixel (vertically centered, horizontally centered)
      val centerIdx2 = (400 * 1600 + 800) * 4
      val centerG2 = img2(centerIdx2 + 1) & 0xFF
      centerG2 should be > 50

      // Sphere should not have moved significantly
      Math.abs(centerG2 - centerG1) should be < 100

    } finally {
      renderer.dispose()
    }
  }
}
