package io.github.lene.optix

import com.typesafe.scalalogging.LazyLogging
import io.github.lene.optix.ColorConstants.HIGHLY_TRANSPARENT_WHITE
import io.github.lene.optix.ColorConstants.PERFORMANCE_TEST_GREEN_CYAN
import io.github.lene.optix.Slow
import io.github.lene.optix.ThresholdConstants.MIN_FPS_RATIO
import io.github.lene.optix.ThresholdConstants.MIN_FPS_RATIO_ANTIALIASING
import io.github.lene.optix.ThresholdConstants.MIN_FPS_RATIO_BUFFER_REUSE
import io.github.lene.optix.ThresholdConstants.STANDARD_IMAGE_SIZE
import menger.common.Const
import menger.common.Vector
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
    renderer.render(performanceSize)

    val startNs = System.nanoTime()
    (0 until iterations).foreach(_ => renderer.render(performanceSize))
    val elapsedNs = System.nanoTime() - startNs

    val durationMs = elapsedNs / 1_000_000.0
    val fps = iterations * 1000.0 / durationMs

    logger.info(f"$testName: $iterations renders at ${performanceSize.width}x${performanceSize.height} in $durationMs%.2fms @$fps%.1f fps")

    fps

  // Same-run calibration probe (Sprint 36 D2): a minimal, fixed scene distinct from all
  // 5 tested scenarios below, measured once via the same measureAndLog path. `lazy`
  // because `renderer` only exists inside a running test's beforeEach — this evaluates
  // on whichever test first touches it and is cached for the rest of the suite, so every
  // fps assertion below judges its scenario as a fraction of THIS run's own probe rather
  // than an absolute floor. Hot/cold GPU state (thermal throttling, prior-test warmup)
  // then cancels out, superseding the hand-tuned MIN_FPS_ANTIALIASING workaround whose
  // own comment already flagged this as the "proper long-term fix."
  private lazy val calibrationFps: Double = measureAndLog("Calibration probe (same-run baseline)"):
    TestScenario.default().applyTo(renderer)

  "Performance" should "achieve the FPS ratio floor for opaque spheres" taggedAs (Slow) in:
    val fps = measureAndLog("Opaque sphere"):
      TestScenario.performanceBaseline()
        .withPlane(1, false, -2.0f)
        .applyTo(renderer)

    fps / calibrationFps should be > MIN_FPS_RATIO

  it should "achieve the FPS ratio floor for transparent spheres" taggedAs (Slow) in:
    val fps = measureAndLog("Transparent sphere"):
      TestScenario.performanceTransparent()
        .withIOR(Const.iorGlass)
        .withPlane(1, false, -2.0f)
        .applyTo(renderer)

    fps / calibrationFps should be > MIN_FPS_RATIO

  it should "achieve the FPS ratio floor for high-IOR materials" taggedAs (Slow) in:
    val fps = measureAndLog("Diamond material"):
      TestScenario.diamondSphere()
        .withSphereColor(HIGHLY_TRANSPARENT_WHITE)
        .withPlane(1, false, -2.0f)
        .applyTo(renderer)

    fps / calibrationFps should be > MIN_FPS_RATIO

  it should "achieve the FPS ratio floor for large spheres" taggedAs (Slow) in:
    val fps = measureAndLog("Large sphere"):
      TestScenario.largeSphere()
        .withSphereColor(PERFORMANCE_TEST_GREEN_CYAN)
        .withIOR(Const.iorGlass)
        .withSphereRadius(2.0f)
        .withPlane(1, false, -2.0f)
        .applyTo(renderer)

    fps / calibrationFps should be > MIN_FPS_RATIO

  it should "achieve the FPS ratio floor with buffer reuse" in:
    val fps = measureAndLog("Buffer reuse"):
      renderer.setSphere(Vector[3](0.0f, 0.0f, 0.0f), 1.5f)
      renderer.setCamera(
        Vector[3](0.0f, 0.0f, 3.0f),
        Vector[3](0.0f, 0.0f, 0.0f),
        Vector[3](0.0f, 1.0f, 0.0f),
        60f
      )

    fps / calibrationFps should be > MIN_FPS_RATIO_BUFFER_REUSE

  it should "stay above the antialiasing FPS ratio floor" taggedAs (Slow) in:
    val fps = measureAndLog("Antialiasing"):
      TestScenario.default()
        .withSphereRadius(0.5f)
        .withPlane(1, false, -2.0f)
        .applyTo(renderer)
      renderer.setAntialiasing(enabled = true, maxDepth = 2, threshold = 0.1f)

    fps / calibrationFps should be > MIN_FPS_RATIO_ANTIALIASING
