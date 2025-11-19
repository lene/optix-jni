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

      val size1 = ImageSize(800, 800)
      renderer.updateImageDimensions(size1)
      renderer.setCamera(eye, lookAt, up, fov)

      // Render at 800x800
      val img1 = renderer.render(size1).get
      img1.length shouldBe ImageValidation.imageByteSize(size1)

      // Sample the center pixel - sphere should be visible
      val centerIdx1 = (400 * 800 + 400) * 4
      val centerR1 = img1(centerIdx1) & 0xFF
      centerR1 should be > 50 // Should have some red from the sphere

      // Now resize to wide aspect ratio (1600x800) - 2:1
      val size2 = ImageSize(1600, 800)
      renderer.updateImageDimensions(size2)
      renderer.setCamera(eye, lookAt, up, fov)

      // Render at 1600x800
      val img2 = renderer.render(size2).get
      img2.length shouldBe ImageValidation.imageByteSize(size2)

      // Sample the center pixel - sphere should still be visible and similar brightness
      val centerIdx2 = (400 * 1600 + 800) * 4
      val centerR2 = img2(centerIdx2) & 0xFF

      // Center should still show the sphere with similar intensity
      // (allowing for some variation due to different sampling)
      centerR2 should be > 50
      Math.abs(centerR2 - centerR1) should be < 100

      // Now resize to tall aspect ratio (800x1600) - 1:2
      val size3 = ImageSize(800, 1600)
      renderer.updateImageDimensions(size3)
      renderer.setCamera(eye, lookAt, up, fov)

      // Render at 800x1600
      val img3 = renderer.render(size3).get
      img3.length shouldBe ImageValidation.imageByteSize(size3)

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
      val size1 = ImageSize(800, 800)
      renderer.updateImageDimensions(size1)
      renderer.setCamera(eye, lookAt, up, fov)
      val img1 = renderer.render(size1).get

      // Center pixel should show the green sphere
      val centerIdx1 = (400 * 800 + 400) * 4
      val centerG1 = img1(centerIdx1 + 1) & 0xFF
      centerG1 should be > 50

      // Render at wide aspect ratio - sphere should still be centered vertically
      val size2 = ImageSize(1600, 800)
      renderer.updateImageDimensions(size2)
      renderer.setCamera(eye, lookAt, up, fov)
      val img2 = renderer.render(size2).get

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
