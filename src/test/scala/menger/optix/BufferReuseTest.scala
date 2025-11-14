package menger.optix

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import com.typesafe.scalalogging.LazyLogging

/**
 * Tests for GPU buffer reuse optimization.
 *
 * Verifies that the image buffer is properly cached and reused across
 * multiple render calls with the same dimensions, providing performance
 * improvements for animations and repeated renders.
 */
class BufferReuseTest extends AnyFlatSpec with Matchers with LazyLogging {

  "GPU buffer reuse" should "work correctly with same dimensions" in {
    assume(OptiXRenderer.isLibraryLoaded, "OptiX native library not loaded")

    val renderer = new OptiXRenderer()

    try {
      renderer.initialize() should be (true)
      renderer.setSphere(0.0f, 0.0f, 0.0f, 1.5f)

      val eye = Array(0.0f, 0.0f, 3.0f)
      val lookAt = Array(0.0f, 0.0f, 0.0f)
      val up = Array(0.0f, 1.0f, 0.0f)
      renderer.setCamera(eye, lookAt, up, 60f)

      // Render multiple times with same dimensions (should reuse buffer)
      val img1 = renderer.render(800, 600).get
      val img2 = renderer.render(800, 600).get
      val img3 = renderer.render(800, 600).get

      img1.length should be (800 * 600 * 4)
      img2.length should be (800 * 600 * 4)
      img3.length should be (800 * 600 * 4)

      // All should have identical content (same scene, same camera)
      img1 should equal (img2)
      img2 should equal (img3)

    } finally {
      renderer.dispose()
    }
  }

  it should "reallocate when dimensions change" in {
    assume(OptiXRenderer.isLibraryLoaded, "OptiX native library not loaded")

    val renderer = new OptiXRenderer()

    try {
      renderer.initialize() should be (true)
      renderer.setSphere(0.0f, 0.0f, 0.0f, 1.5f)

      val eye = Array(0.0f, 0.0f, 3.0f)
      val lookAt = Array(0.0f, 0.0f, 0.0f)
      val up = Array(0.0f, 1.0f, 0.0f)
      renderer.setCamera(eye, lookAt, up, 60f)

      // Render at different resolutions
      val img1 = renderer.render(640, 480).get
      val img2 = renderer.render(1024, 768).get
      val img3 = renderer.render(800, 600).get
      val img4 = renderer.render(800, 600).get  // Same as img3, should reuse buffer

      img1.length should be (640 * 480 * 4)
      img2.length should be (1024 * 768 * 4)
      img3.length should be (800 * 600 * 4)
      img4.length should be (800 * 600 * 4)

      // Same dimensions should produce same output
      img3 should equal (img4)

    } finally {
      renderer.dispose()
    }
  }

  it should "handle changing content with buffer reuse" in {
    assume(OptiXRenderer.isLibraryLoaded, "OptiX native library not loaded")

    val renderer = new OptiXRenderer()

    try {
      renderer.initialize() should be (true)

      val eye = Array(0.0f, 0.0f, 3.0f)
      val lookAt = Array(0.0f, 0.0f, 0.0f)
      val up = Array(0.0f, 1.0f, 0.0f)
      renderer.setCamera(eye, lookAt, up, 60f)

      // Render with different sphere positions (same dimensions)
      renderer.setSphere(0.0f, 0.0f, 0.0f, 1.5f)
      val img1 = renderer.render(400, 300).get

      renderer.setSphere(1.0f, 0.0f, 0.0f, 1.5f)
      val img2 = renderer.render(400, 300).get

      renderer.setSphere(0.0f, 1.0f, 0.0f, 1.5f)
      val img3 = renderer.render(400, 300).get

      // All renders should succeed
      img1.length should be (400 * 300 * 4)
      img2.length should be (400 * 300 * 4)
      img3.length should be (400 * 300 * 4)

      // Images should be different (different sphere positions)
      img1 should not equal img2
      img2 should not equal img3
      img1 should not equal img3

    } finally {
      renderer.dispose()
    }
  }

  it should "maintain performance with repeated renders" in {
    assume(OptiXRenderer.isLibraryLoaded, "OptiX native library not loaded")

    // Skip performance test under compute-sanitizer (20x slowdown from instrumentation)
    val runningUnderSanitizer = sys.env.get("RUNNING_UNDER_COMPUTE_SANITIZER").contains("true")
    assume(!runningUnderSanitizer, "Performance test skipped under compute-sanitizer instrumentation")

    val renderer = new OptiXRenderer()

    try {
      renderer.initialize() should be (true)
      renderer.setSphere(0.0f, 0.0f, 0.0f, 1.5f)

      val eye = Array(0.0f, 0.0f, 3.0f)
      val lookAt = Array(0.0f, 0.0f, 0.0f)
      val up = Array(0.0f, 1.0f, 0.0f)
      renderer.setCamera(eye, lookAt, up, 60f)

      // Warmup
      renderer.render(800, 600).get

      // Time 100 renders at same resolution (should benefit from buffer reuse)
      val iterations = 100
      val start = System.nanoTime()

      for (i <- 0 until iterations) {
        val img = renderer.render(800, 600).get
        img.length should be (800 * 600 * 4)
      }

      val end = System.nanoTime()
      val durationMs = (end - start) / 1000000.0
      val fps = (iterations * 1000.0) / durationMs

      logger.info(f"Performance test: $iterations renders at 800x600 in ${durationMs}%.2fms @${fps}%.1ffps")

      // Performance check: should achieve at least 50 FPS (20ms per frame)
      // With buffer reuse, this should be easily achievable
      fps should be > 50.0

    } finally {
      renderer.dispose()
    }
  }

  it should "work correctly after dispose and re-initialize" in {
    assume(OptiXRenderer.isLibraryLoaded, "OptiX native library not loaded")

    val renderer = new OptiXRenderer()

    try {
      // First session
      renderer.initialize() should be (true)
      renderer.setSphere(0.0f, 0.0f, 0.0f, 1.5f)
      val img1 = renderer.render(400, 300).get
      img1.length should be (400 * 300 * 4)

      // Dispose (should free cached buffer)
      renderer.dispose()

      // Second session (buffer should be reallocated)
      renderer.initialize() should be (true)
      renderer.setSphere(0.0f, 0.0f, 0.0f, 1.5f)
      val img2 = renderer.render(400, 300).get
      img2.length should be (400 * 300 * 4)

      // Should produce same output (same scene)
      img1 should equal (img2)

    } finally {
      renderer.dispose()
    }
  }
}
