package menger.optix

import com.typesafe.scalalogging.LazyLogging
import menger.common.Const
import menger.common.ImageSize
import menger.common.Vector
import menger.optix.ColorConstants.HIGHLY_TRANSPARENT_WHITE
import menger.optix.ColorConstants.PERFORMANCE_TEST_GREEN_CYAN
import menger.optix.Slow
import menger.optix.ThresholdConstants.MIN_FPS
import menger.optix.ThresholdConstants.STANDARD_IMAGE_SIZE
import menger.optix.ThresholdConstants.TEST_IMAGE_SIZE
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers


class PerformanceSuite extends AnyFlatSpec
    with Matchers
    with LazyLogging
    with RendererFixture:

  private val runningUnderSanitizer: Boolean =
    sys.env.get("RUNNING_UNDER_COMPUTE_SANITIZER").contains("true")

  private val performanceSize = STANDARD_IMAGE_SIZE
  private val iterations = 100

  // Ensure library is loaded before running tests
  OptiXRenderer.isLibraryLoaded shouldBe true

  private def measureAndLog(testName: String)(setup: => Unit): Double =
    assume(!runningUnderSanitizer, "Performance test skipped under compute-sanitizer instrumentation")

    setup

    // Warmup render
    renderer.render(performanceSize).get

    val startNs = System.nanoTime()
    (0 until iterations).foreach(_ => renderer.render(performanceSize).get)
    val elapsedNs = System.nanoTime() - startNs

    val durationMs = elapsedNs / 1_000_000.0
    val fps = iterations * 1000.0 / durationMs

    logger.info(f"$testName: $iterations renders at ${performanceSize.width}x${performanceSize.height} in $durationMs%.2fms @$fps%.1f fps")

    fps

  "Performance" should "achieve >10 FPS for opaque spheres" taggedAs (Slow) in:
    val fps = measureAndLog("Opaque sphere"):
      TestScenario.performanceBaseline()
        .withPlane(1, false, -2.0f)
        .applyTo(renderer)

    fps should be > MIN_FPS

  it should "achieve >10 FPS for transparent spheres" taggedAs (Slow) in:
    val fps = measureAndLog("Transparent sphere"):
      TestScenario.performanceTransparent()
        .withIOR(Const.iorGlass)
        .withPlane(1, false, -2.0f)
        .applyTo(renderer)

    fps should be > MIN_FPS

  it should "achieve >10 FPS for high-IOR materials" taggedAs (Slow) in:
    val fps = measureAndLog("Diamond material"):
      TestScenario.diamondSphere()
        .withSphereColor(HIGHLY_TRANSPARENT_WHITE)
        .withPlane(1, false, -2.0f)
        .applyTo(renderer)

    fps should be > MIN_FPS

  it should "achieve >10 FPS for large spheres" taggedAs (Slow) in:
    val fps = measureAndLog("Large sphere"):
      TestScenario.largeSphere()
        .withSphereColor(PERFORMANCE_TEST_GREEN_CYAN)
        .withIOR(Const.iorGlass)
        .withSphereRadius(2.0f)
        .withPlane(1, false, -2.0f)
        .applyTo(renderer)

    fps should be > MIN_FPS

  it should "achieve >50 FPS with buffer reuse" in:
    val fps = measureAndLog("Buffer reuse"):
      renderer.setSphere(Vector[3](0.0f, 0.0f, 0.0f), 1.5f)
      renderer.setCamera(
        Vector[3](0.0f, 0.0f, 3.0f),
        Vector[3](0.0f, 0.0f, 0.0f),
        Vector[3](0.0f, 1.0f, 0.0f),
        60f
      )

    fps should be > 50.0

  it should "achieve >10 FPS with antialiasing enabled" taggedAs (Slow) in:
    val fps = measureAndLog("Antialiasing"):
      TestScenario.default()
        .withSphereRadius(0.5f)
        .withPlane(1, false, -2.0f)
        .applyTo(renderer)
      renderer.setAntialiasing(enabled = true, maxDepth = 2, threshold = 0.1f)

    fps should be > MIN_FPS
