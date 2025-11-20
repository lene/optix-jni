package menger.optix

import menger.common.ImageSize
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import com.typesafe.scalalogging.LazyLogging

import ThresholdConstants.*


class MultiInstanceTest extends AnyFlatSpec with Matchers with LazyLogging {

  "Multiple OptiXRenderer instances" should "initialize independently" in {
    assume(OptiXRenderer.isLibraryLoaded, "OptiX native library not loaded")

    val renderer1 = new OptiXRenderer()
    val renderer2 = new OptiXRenderer()

    try {
      val init1 = renderer1.initialize()
      val init2 = renderer2.initialize()

      init1 should be (true)
      init2 should be (true)

      // Verify instances have different native handles
      renderer1.nativeHandle should not be 0L
      renderer2.nativeHandle should not be 0L
      renderer1.nativeHandle should not equal renderer2.nativeHandle

    } finally {
      renderer1.dispose()
      renderer2.dispose()
    }
  }

  it should "maintain independent sphere state" in {
    assume(OptiXRenderer.isLibraryLoaded, "OptiX native library not loaded")

    val renderer1 = new OptiXRenderer()
    val renderer2 = new OptiXRenderer()

    try {
      renderer1.initialize() should be (true)
      renderer2.initialize() should be (true)

      // Set different spheres in each renderer - same position, different colors
      renderer1.setSphere(0.0f, 0.0f, 0.0f, 1.5f)
      renderer1.setSphereColor(1.0f, 0.0f, 0.0f, 1.0f) // Red sphere

      renderer2.setSphere(0.0f, 0.0f, 0.0f, 1.5f)
      renderer2.setSphereColor(0.0f, 0.0f, 1.0f, 1.0f) // Blue sphere (same position)

      // Set same camera for both
      val eye = Array(0.0f, 0.0f, 3.0f)
      val lookAt = Array(0.0f, 0.0f, 0.0f)
      val up = Array(0.0f, 1.0f, 0.0f)
      renderer1.setCamera(eye, lookAt, up, 60f)
      renderer2.setCamera(eye, lookAt, up, 60f)

      // Render both
      val size = TEST_IMAGE_SIZE
      val img1 = renderer1.render(size)
      val img2 = renderer2.render(size)

      img1 shouldBe defined
      img2 shouldBe defined
      img1.get.length should be (ImageValidation.imageByteSize(size))
      img2.get.length should be (ImageValidation.imageByteSize(size))

      // Images should be different (different spheres)
      img1.get should not equal img2.get

      // Count pixels with dominant red vs blue
      def countRedPixels(img: Array[Byte]): Int = {
        img.grouped(4).count { pixel =>
          val r = (pixel(0) & 0xFF)
          val g = (pixel(1) & 0xFF)
          val b = (pixel(2) & 0xFF)
          r > g && r > b && r > 100
        }
      }

      def countBluePixels(img: Array[Byte]): Int = {
        img.grouped(4).count { pixel =>
          val r = (pixel(0) & 0xFF)
          val g = (pixel(1) & 0xFF)
          val b = (pixel(2) & 0xFF)
          b > r && b > g && b > 100
        }
      }

      val red1 = countRedPixels(img1.get)
      val blue1 = countBluePixels(img1.get)
      val red2 = countRedPixels(img2.get)
      val blue2 = countBluePixels(img2.get)

      // Renderer 1 should have more red pixels
      red1 should be > blue1

      // Renderer 2 should have more blue pixels
      blue2 should be > red2

    } finally {
      renderer1.dispose()
      renderer2.dispose()
    }
  }

  it should "render different resolutions independently" in {
    assume(OptiXRenderer.isLibraryLoaded, "OptiX native library not loaded")

    val renderer1 = new OptiXRenderer()
    val renderer2 = new OptiXRenderer()

    try {
      renderer1.initialize() should be (true)
      renderer2.initialize() should be (true)

      // Setup same scene
      renderer1.setSphere(0.0f, 0.0f, 0.0f, 1.5f)
      renderer2.setSphere(0.0f, 0.0f, 0.0f, 1.5f)

      val eye = Array(0.0f, 0.0f, 3.0f)
      val lookAt = Array(0.0f, 0.0f, 0.0f)
      val up = Array(0.0f, 1.0f, 0.0f)
      renderer1.setCamera(eye, lookAt, up, 60f)
      renderer2.setCamera(eye, lookAt, up, 60f)

      // Render at different resolutions
      val size1 = ImageSize(640, 480)
      val size2 = ImageSize(1024, 768)
      val img1 = renderer1.render(size1)
      val img2 = renderer2.render(size2)

      img1.get.length should be (ImageValidation.imageByteSize(size1))
      img2.get.length should be (ImageValidation.imageByteSize(size2))

      // Both should have rendered successfully (non-zero pixels)
      val nonZero1 = img1.get.count(_ != 0)
      val nonZero2 = img2.get.count(_ != 0)

      nonZero1 should be > 0
      nonZero2 should be > 0

    } finally {
      renderer1.dispose()
      renderer2.dispose()
    }
  }

  it should "allow interleaved rendering" in {
    assume(OptiXRenderer.isLibraryLoaded, "OptiX native library not loaded")

    val renderer1 = new OptiXRenderer()
    val renderer2 = new OptiXRenderer()

    try {
      renderer1.initialize() should be (true)
      renderer2.initialize() should be (true)

      // Setup
      renderer1.setSphere(0.0f, 0.0f, 0.0f, 1.5f)
      renderer2.setSphere(1.0f, 1.0f, 1.0f, 2.0f)

      val eye = Array(0.0f, 0.0f, 3.0f)
      val lookAt = Array(0.0f, 0.0f, 0.0f)
      val up = Array(0.0f, 1.0f, 0.0f)
      renderer1.setCamera(eye, lookAt, up, 60f)
      renderer2.setCamera(eye, lookAt, up, 60f)

      // Interleave renders
      val size = TEST_IMAGE_SIZE
      val img1a = renderer1.render(size)
      val img2a = renderer2.render(size)
      val img1b = renderer1.render(size)
      val img2b = renderer2.render(size)

      // All renders should succeed
      img1a.get.length should be (ImageValidation.imageByteSize(size))
      img2a.get.length should be (ImageValidation.imageByteSize(size))
      img1b.get.length should be (ImageValidation.imageByteSize(size))
      img2b.get.length should be (ImageValidation.imageByteSize(size))

      // Same renderer should produce same image
      img1a.get should equal (img1b.get)
      img2a.get should equal (img2b.get)

      // Different renderers should produce different images
      img1a.get should not equal img2a.get

    } finally {
      renderer1.dispose()
      renderer2.dispose()
    }
  }

  it should "dispose independently" in {
    assume(OptiXRenderer.isLibraryLoaded, "OptiX native library not loaded")

    val renderer1 = new OptiXRenderer()
    val renderer2 = new OptiXRenderer()

    renderer1.initialize() should be (true)
    renderer2.initialize() should be (true)

    val handle1 = renderer1.nativeHandle
    val handle2 = renderer2.nativeHandle

    // Dispose first renderer
    renderer1.dispose()
    renderer1.nativeHandle should be (0L)

    // Second renderer should still work
    val size = TEST_IMAGE_SIZE
    renderer2.setSphere(0.0f, 0.0f, 0.0f, 1.5f)
    val img = renderer2.render(size)
    img.get.length should be (ImageValidation.imageByteSize(size))

    // Dispose second renderer
    renderer2.dispose()
    renderer2.nativeHandle should be (0L)
  }

  it should "support creating many instances sequentially" in {
    assume(OptiXRenderer.isLibraryLoaded, "OptiX native library not loaded")

    val instanceCount = 10
    val handles = collection.mutable.Set[Long]()

    for (i <- 0 until instanceCount) {
      val renderer = new OptiXRenderer()
      try {
        renderer.initialize() should be (true)
        val handle = renderer.nativeHandle

        // Each instance should have unique handle
        handles should not contain handle
        handles.add(handle)

        // Verify it works
        val size = ImageSize(200, 200)
        renderer.setSphere(0.0f, 0.0f, 0.0f, 1.5f)
        val img = renderer.render(size).get
        img.length should be (ImageValidation.imageByteSize(size))

      } finally {
        renderer.dispose()
      }
    }

    handles.size should be (instanceCount)
  }

  it should "reject operations on uninitialized instance" in {
    assume(OptiXRenderer.isLibraryLoaded, "OptiX native library not loaded")

    val renderer = new OptiXRenderer()

    renderer.nativeHandle should be (0L)

    // Operations on uninitialized instance should fail gracefully
    noException should be thrownBy renderer.setSphere(0, 0, 0, 1)
    noException should be thrownBy renderer.setSphereColor(1, 0, 0, 1)

    // render should return None on uninitialized instance
    val img = renderer.render(TEST_IMAGE_SIZE)
    img shouldBe None
  }

  it should "allow re-initialization check" in {
    assume(OptiXRenderer.isLibraryLoaded, "OptiX native library not loaded")

    val renderer = new OptiXRenderer()

    try {
      renderer.initialize() should be (true)
      val handle1 = renderer.nativeHandle

      // Second initialize should return true (already initialized)
      renderer.initialize() should be (true)
      val handle2 = renderer.nativeHandle

      // Handle should not change
      handle2 should equal (handle1)

    } finally {
      renderer.dispose()
    }
  }

  it should "be idempotent - multiple initialize() calls are safe" in {
    assume(OptiXRenderer.isLibraryLoaded, "OptiX native library not loaded")

    val renderer = new OptiXRenderer()

    try {
      // Call initialize multiple times
      renderer.initialize() should be (true)
      renderer.initialize() should be (true)
      renderer.initialize() should be (true)

      val handle = renderer.nativeHandle
      handle should not be 0L

      // Renderer should still work normally
      val size = ImageSize(200, 200)
      renderer.setSphere(0.0f, 0.0f, 0.0f, 1.5f)
      val img = renderer.render(size).get
      img.length should be (ImageValidation.imageByteSize(size))

    } finally {
      renderer.dispose()
    }
  }

  it should "work correctly with isAvailable() followed by explicit initialize()" in {
    assume(OptiXRenderer.isLibraryLoaded, "OptiX native library not loaded")

    val renderer = new OptiXRenderer()

    try {
      // This is the pattern used by ensureAvailable()
      renderer.isAvailable should be (true)  // Calls initialize() internally
      renderer.initialize() should be (true) // Should be idempotent

      val handle = renderer.nativeHandle
      handle should not be 0L

      // Renderer should work
      val size = ImageSize(200, 200)
      renderer.setSphere(0.0f, 0.0f, 0.0f, 1.5f)
      val img = renderer.render(size).get
      img.length should be (ImageValidation.imageByteSize(size))

    } finally {
      renderer.dispose()
    }
  }
}
