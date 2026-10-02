package io.github.lene.optix

import io.github.lene.optix.ColorConstants.HIGHLY_TRANSPARENT_WHITE
import io.github.lene.optix.ColorConstants.PERFORMANCE_TEST_GREEN_CYAN
import io.github.lene.optix.ThresholdConstants.MAX_SLOWDOWN_ANTIALIASING
import io.github.lene.optix.ThresholdConstants.MAX_SLOWDOWN_BUFFER_REUSE
import io.github.lene.optix.ThresholdConstants.MAX_SLOWDOWN_DIAMOND
import io.github.lene.optix.ThresholdConstants.MAX_SLOWDOWN_LARGE_SPHERE
import io.github.lene.optix.ThresholdConstants.MAX_SLOWDOWN_OPAQUE
import io.github.lene.optix.ThresholdConstants.MAX_SLOWDOWN_TRANSPARENT
import io.github.lene.optix.ThresholdConstants.STANDARD_IMAGE_SIZE
import io.github.lene.qa.Perf
import io.github.lene.qa.PerfGate
import io.github.lene.qa.RelativeBenchmark
import io.github.lene.qa.Side
import menger.common.Const
import menger.common.Vector
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Render-time gates. Each scene is timed against a reference scene on the same renderer, in
  * interleaved rounds (see io.github.lene.qa.RelativeBenchmark), so GPU clock changes and
  * background load affect both sides of every comparison equally. */
class PerformanceSuite extends AnyFlatSpec
    with Matchers
    with PerfGate
    with RendererFixture:

  // Ensure library is loaded before running tests
  OptiXRenderer.isLibraryLoaded shouldBe true

  private def render(): Unit =
    val _ = renderer.render(STANDARD_IMAGE_SIZE)

  private def scene(name: String)(setup: => Unit): Side =
    Side(name, () => render(), prepare = () => setup)

  private val defaultScene = scene("default scene")(TestScenario.default().applyTo(renderer))

  // Perf-tagged tests run only under PERF_ONLY=1, never under compute-sanitizer (#47).
  private def gate(subject: Side, maxSlowdown: Double, reference: Side = defaultScene) =
    assertWithin(
      s"${subject.name} vs ${reference.name}",
      RelativeBenchmark.compare(reference, subject, maxSlowdown)
    )

  "Performance" should "render opaque spheres within their slowdown limit" taggedAs Perf in:
    val opaque = scene("opaque sphere"):
      TestScenario.performanceBaseline()
        .withPlane(1, false, -2.0f)
        .applyTo(renderer)
    gate(opaque, MAX_SLOWDOWN_OPAQUE)

  it should "render transparent spheres within their slowdown limit" taggedAs Perf in:
    val transparent = scene("transparent sphere"):
      TestScenario.performanceTransparent()
        .withIOR(Const.iorGlass)
        .withPlane(1, false, -2.0f)
        .applyTo(renderer)
    gate(transparent, MAX_SLOWDOWN_TRANSPARENT)

  it should "render high-IOR materials within their slowdown limit" taggedAs Perf in:
    val diamond = scene("diamond sphere"):
      TestScenario.diamondSphere()
        .withSphereColor(HIGHLY_TRANSPARENT_WHITE)
        .withPlane(1, false, -2.0f)
        .applyTo(renderer)
    gate(diamond, MAX_SLOWDOWN_DIAMOND)

  it should "render large spheres within their slowdown limit" taggedAs Perf in:
    val large = scene("large sphere"):
      TestScenario.largeSphere()
        .withSphereColor(PERFORMANCE_TEST_GREEN_CYAN)
        .withIOR(Const.iorGlass)
        .withSphereRadius(2.0f)
        .withPlane(1, false, -2.0f)
        .applyTo(renderer)
    gate(large, MAX_SLOWDOWN_LARGE_SPHERE)

  it should "render with buffer reuse within its slowdown limit" taggedAs Perf in:
    // A fully defined scene (#47: it used to set only sphere and camera and inherit the
    // reference scene's plane and light in interleaved rounds).
    val bufferReuse = scene("buffer reuse"):
      TestScenario.default()
        .withSphereRadius(1.5f)
        .withCameraEye(Vector[3](0.0f, 0.0f, 3.0f))
        .withCameraLookAt(Vector[3](0.0f, 0.0f, 0.0f))
        .withCameraUp(Vector[3](0.0f, 1.0f, 0.0f))
        .withHorizontalFOV(60f)
        .applyTo(renderer)
    gate(bufferReuse, MAX_SLOWDOWN_BUFFER_REUSE)

  it should "keep the antialiasing overhead within its limit" taggedAs Perf in:
    def antialiasingScene(enabled: Boolean): Unit =
      TestScenario.default()
        .withSphereRadius(0.5f)
        .withPlane(1, false, -2.0f)
        .applyTo(renderer)
      renderer.setAntialiasing(enabled = enabled, maxDepth = 2, threshold = 0.1f)
    gate(
      scene("antialiasing on")(antialiasingScene(enabled = true)),
      MAX_SLOWDOWN_ANTIALIASING,
      reference = scene("antialiasing off")(antialiasingScene(enabled = false))
    )
