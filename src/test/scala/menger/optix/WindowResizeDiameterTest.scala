package menger.optix

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import com.typesafe.scalalogging.LazyLogging
import org.scalatest.BeforeAndAfterAll

class WindowResizeDiameterTest extends AnyFlatSpec with Matchers with LazyLogging with BeforeAndAfterAll {

  // Baseline measurements shared between tests
  private var baselineVDiam: Int = 0
  private var baselineHDiam: Int = 0

  /**
   * Detect if a pixel is red using the threshold r > 2*g AND r > 2*b
   */
  private def isRedPixel(r: Int, g: Int, b: Int): Boolean = {
    r > 2 * g && r > 2 * b
  }

  /**
   * Measure the vertical diameter of the sphere by scanning the center column.
   * Returns the number of pixels where the sphere is visible.
   */
  private def measureVerticalDiameter(img: Array[Byte], width: Int, height: Int): Int = {
    val centerX = width / 2
    var count = 0
    for (y <- 0 until height) {
      val idx = (y * width + centerX) * 4
      val r = img(idx) & 0xFF
      val g = img(idx + 1) & 0xFF
      val b = img(idx + 2) & 0xFF
      if (isRedPixel(r, g, b)) {
        count += 1
      }
    }
    count
  }

  /**
   * Measure the horizontal diameter of the sphere by scanning the center row.
   * Returns the number of pixels where the sphere is visible.
   */
  private def measureHorizontalDiameter(img: Array[Byte], width: Int, height: Int): Int = {
    val centerY = height / 2
    var count = 0
    for (x <- 0 until width) {
      val idx = (centerY * width + x) * 4
      val r = img(idx) & 0xFF
      val g = img(idx + 1) & 0xFF
      val b = img(idx + 2) & 0xFF
      if (isRedPixel(r, g, b)) {
        count += 1
      }
    }
    count
  }

  override def beforeAll(): Unit = {
    super.beforeAll()

    // Only run baseline if OptiX is available
    if (OptiXRenderer.isLibraryLoaded) {
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

        // Render at 800x800 (square baseline)
        renderer.updateImageDimensions(800, 800)
        renderer.setCamera(eye, lookAt, up, fov)
        val img = renderer.render(800, 800).get

        baselineVDiam = measureVerticalDiameter(img, 800, 800)
        baselineHDiam = measureHorizontalDiameter(img, 800, 800)
        logger.info(s"800x800 baseline: vertical=${baselineVDiam}px, horizontal=${baselineHDiam}px")

        // In square aspect, vertical and horizontal should be approximately equal
        Math.abs(baselineVDiam - baselineHDiam) should be < 5

      } finally {
        renderer.dispose()
      }
    }
  }

  "OptiXRenderer" should "scale sphere uniformly when window width changes" in {
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

      // Render at 1600x800 (wide 2:1 - width doubled)
      renderer.updateImageDimensions(1600, 800)
      renderer.setCamera(eye, lookAt, up, fov)
      val img2 = renderer.render(1600, 800).get
      val vDiam2 = measureVerticalDiameter(img2, 1600, 800)
      val hDiam2 = measureHorizontalDiameter(img2, 1600, 800)
      logger.info(s"1600x800: vertical=${vDiam2}px, horizontal=${hDiam2}px")

      // CRITICAL: When width doubles, sphere should scale uniformly in BOTH directions
      // Both vertical and horizontal diameters should double (100% increase)
      Math.abs(vDiam2 - 2 * baselineVDiam) should be < 10
      Math.abs(hDiam2 - 2 * baselineHDiam) should be < 10

    } finally {
      renderer.dispose()
    }
  }

  it should "not scale sphere when window height changes" in {
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

      // Render at 800x1600 (tall 1:2 - height doubled)
      renderer.updateImageDimensions(800, 1600)
      renderer.setCamera(eye, lookAt, up, fov)
      val img3 = renderer.render(800, 1600).get
      val vDiam3 = measureVerticalDiameter(img3, 800, 1600)
      val hDiam3 = measureHorizontalDiameter(img3, 800, 1600)
      logger.info(s"800x1600: vertical=${vDiam3}px, horizontal=${hDiam3}px")

      // CRITICAL: When height doubles, sphere size should NOT change
      // Both vertical and horizontal diameters should stay the same
      Math.abs(vDiam3 - baselineVDiam) should be < 10
      Math.abs(hDiam3 - baselineHDiam) should be < 10

    } finally {
      renderer.dispose()
    }
  }
}
