package menger.optix
import menger.common.Vector

import menger.common.ImageSize
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import com.typesafe.scalalogging.LazyLogging

import ThresholdConstants.*


class BufferReuseTest extends AnyFlatSpec with Matchers with LazyLogging {

  "GPU buffer reuse" should "work correctly with same dimensions" in {
    assume(OptiXRenderer.isLibraryLoaded, "OptiX native library not loaded")

    val renderer = new OptiXRenderer()

    try {
      renderer.initialize() should be (true)
      renderer.setSphere(Vector[3](0.0f, 0.0f, 0.0f), 1.5f)

      val eye = Vector[3](0.0f, 0.0f, 3.0f)
      val lookAt = Vector[3](0.0f, 0.0f, 0.0f)
      val up = Vector[3](0.0f, 1.0f, 0.0f)
      renderer.setCamera(eye, lookAt, up, 60f)

      // Render multiple times with same dimensions (should reuse buffer)
      val size = STANDARD_IMAGE_SIZE
      val img1 = renderer.render(size).get
      val img2 = renderer.render(size).get
      val img3 = renderer.render(size).get

      img1.length should be (ImageValidation.imageByteSize(size))
      img2.length should be (ImageValidation.imageByteSize(size))
      img3.length should be (ImageValidation.imageByteSize(size))

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
      renderer.setSphere(Vector[3](0.0f, 0.0f, 0.0f), 1.5f)

      val eye = Vector[3](0.0f, 0.0f, 3.0f)
      val lookAt = Vector[3](0.0f, 0.0f, 0.0f)
      val up = Vector[3](0.0f, 1.0f, 0.0f)
      renderer.setCamera(eye, lookAt, up, 60f)

      // Render at different resolutions
      val size1 = ImageSize(640, 480)
      val size2 = ImageSize(1024, 768)
      val size3 = STANDARD_IMAGE_SIZE
      val img1 = renderer.render(size1).get
      val img2 = renderer.render(size2).get
      val img3 = renderer.render(size3).get
      val img4 = renderer.render(size3).get  // Same as img3, should reuse buffer

      img1.length should be (ImageValidation.imageByteSize(size1))
      img2.length should be (ImageValidation.imageByteSize(size2))
      img3.length should be (ImageValidation.imageByteSize(size3))
      img4.length should be (ImageValidation.imageByteSize(size3))

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

      val eye = Vector[3](0.0f, 0.0f, 3.0f)
      val lookAt = Vector[3](0.0f, 0.0f, 0.0f)
      val up = Vector[3](0.0f, 1.0f, 0.0f)
      renderer.setCamera(eye, lookAt, up, 60f)

      // Render with different sphere positions (same dimensions)
      val size = TEST_IMAGE_SIZE
      renderer.setSphere(Vector[3](0.0f, 0.0f, 0.0f), 1.5f)
      val img1 = renderer.render(size).get

      renderer.setSphere(Vector[3](1.0f, 0.0f, 0.0f), 1.5f)
      val img2 = renderer.render(size).get

      renderer.setSphere(Vector[3](0.0f, 1.0f, 0.0f), 1.5f)
      val img3 = renderer.render(size).get

      // All renders should succeed
      img1.length should be (ImageValidation.imageByteSize(size))
      img2.length should be (ImageValidation.imageByteSize(size))
      img3.length should be (ImageValidation.imageByteSize(size))

      // Images should be different (different sphere positions)
      img1 should not equal img2
      img2 should not equal img3
      img1 should not equal img3

    } finally {
      renderer.dispose()
    }
  }

  it should "work correctly after dispose and re-initialize" in {
    assume(OptiXRenderer.isLibraryLoaded, "OptiX native library not loaded")

    val renderer = new OptiXRenderer()

    try {
      // First session
      val size = TEST_IMAGE_SIZE
      renderer.initialize() should be (true)
      renderer.setSphere(Vector[3](0.0f, 0.0f, 0.0f), 1.5f)
      val img1 = renderer.render(size).get
      img1.length should be (ImageValidation.imageByteSize(size))

      // Dispose (should free cached buffer)
      renderer.dispose()

      // Second session (buffer should be reallocated)
      renderer.initialize() should be (true)
      renderer.setSphere(Vector[3](0.0f, 0.0f, 0.0f), 1.5f)
      val img2 = renderer.render(size).get
      img2.length should be (ImageValidation.imageByteSize(size))

      // Should produce same output (same scene)
      img1 should equal (img2)

    } finally {
      renderer.dispose()
    }
  }
}
