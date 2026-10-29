package io.github.lene.optix

import scala.util.Using

import io.github.lene.optix.ThresholdConstants.MAX_SLOWDOWN_CAUSTICS
import io.github.lene.optix.ThresholdConstants.MAX_SLOWDOWN_CUSTOM_GEOMETRY
import io.github.lene.optix.ThresholdConstants.MAX_SLOWDOWN_INSTANCE_MOVE
import io.github.lene.optix.ThresholdConstants.MAX_SLOWDOWN_INSTANCE_SCALING
import io.github.lene.optix.ThresholdConstants.MAX_SLOWDOWN_RENDER_MIX
import io.github.lene.optix.ThresholdConstants.STANDARD_IMAGE_SIZE
import io.github.lene.qa.Perf
import io.github.lene.qa.PerfGate
import io.github.lene.qa.RelativeBenchmark
import io.github.lene.qa.Side
import menger.common.Color
import menger.common.Vector
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Perf gates for what optix-jni publishes and menger's hot paths lean on (review 2026-10-14,
  * optix-jni#67): instance scaling, instance moves (in-place IAS refit, #66), the level-mix
  * render (#85), custom-geometry SPI instances and caustics. Each gate times its subject
  * against a reference scene with RelativeBenchmark; the two sides are separate renderers, so
  * neither has to rebuild the other's scene between rounds. These catch gross regressions: a
  * full IAS rebuild per frame costs 64 small instances only ~8% (measured), well inside the move
  * gate's noise-safe limit, so #66 itself is held by IasInPlaceUpdateSuite's build counter. */
class ScalingPerformanceSuite extends AnyFlatSpec with Matchers with PerfGate:

  OptiXRenderer.isLibraryLoaded shouldBe true

  private val red = Material(Color(0.9f, 0.1f, 0.1f, 1f), 1.0f)
  private val glass = Material(Color(1f, 1f, 1f, 0.05f), 1.5f)

  private def at(x: Float, y: Float, z: Float, scale: Float = 0.2f): Array[Float] =
    Array(scale, 0f, 0f, x, 0f, scale, 0f, y, 0f, 0f, scale, z)

  private def newRenderer(maxInstances: Int = 128): OptiXRenderer =
    val r = new OptiXRenderer()
    r.initialize(maxInstances) shouldBe true
    r.setCamera(Vector[3](0f, 0.5f, 5f), Vector[3](0f, 0f, 0f), Vector[3](0f, 1f, 0f), 60f)
    r.setLight(Vector[3](-0.5f, -1f, -0.5f), 1.0f)
    r

  /** `n` small spheres on a grid; returns their ids. */
  private def grid(r: OptiXRenderer, n: Int): scala.Vector[InstanceId] =
    val side = math.ceil(math.sqrt(n.toDouble)).toInt
    scala.Vector.tabulate(n)(i =>
      r.addSphereInstance(at(-1.5f + 3f * (i % side) / side, -1f + 2f * (i / side) / side, 0f), red))

  private def frame(r: OptiXRenderer): () => Unit = () => { val _ = r.render(STANDARD_IMAGE_SIZE) }

  private def gate(reference: Side, subject: Side, maxSlowdown: Double) =
    assertWithin(s"${subject.name} vs ${reference.name}",
      RelativeBenchmark.compare(reference, subject, maxSlowdown))

  "Instance scaling" should "keep 64 instances within their slowdown of one" taggedAs Perf in:
    Using.resource(newRenderer()): one =>
      Using.resource(newRenderer()): many =>
        grid(one, 1)
        grid(many, 64)
        gate(Side("1 instance", frame(one)), Side("64 instances", frame(many)),
          MAX_SLOWDOWN_INSTANCE_SCALING)

  "Moving an instance every frame" should "cost little more than a static frame" taggedAs Perf in:
    Using.resource(newRenderer()): still =>
      Using.resource(newRenderer()): moving =>
        grid(still, 64)
        val ids = grid(moving, 64)
        val frames = new java.util.concurrent.atomic.AtomicInteger(0)
        val moveThenRender: () => Unit = () =>
          val x = if frames.incrementAndGet() % 2 == 0 then 0.5f else -0.5f
          moving.setInstanceTransform(ids.head, at(x, 1.2f, 0.3f))
          val _ = moving.render(STANDARD_IMAGE_SIZE)
        gate(Side("static frame", frame(still)), Side("instance moved each frame", moveThenRender),
          MAX_SLOWDOWN_INSTANCE_MOVE)

  // renderMix replaces menger's level-mix loop: one render per pass through JNI, each a new
  // byte array, mixed in the JVM. That loop is the reference; renderMix must not be slower. (For
  // this tiny scene the per-pixel mix dominates either way; menger's benchmark scenes gate the
  // cost against the integer-level scene, WP-K.)
  "A two-pass render mix" should "be no slower than rendering and mixing the passes in the JVM" taggedAs Perf in:
    Using.resource(newRenderer()): jvm =>
      Using.resource(newRenderer()): native =>
        val jvmIds = grid(jvm, 2).toArray
        val ids = grid(native, 2).toArray
        val passes = Array(MixPass(Array(true, false), 0.4), MixPass(Array(false, true), 0.6))
        val jvmMix: () => Unit = () =>
          val images = passes.map { pass =>
            jvmIds.zip(pass.visible).foreach((id, v) => jvm.setInstanceVisible(id, v))
            (jvm.render(STANDARD_IMAGE_SIZE), pass.weight)
          }
          val sum = new Array[Double](images.head._1.length)
          images.foreach((image, w) => sum.indices.foreach(i => sum(i) += (image(i) & 0xff) * w))
          val _ = sum.map(v => math.max(0L, math.min(255L, math.round(v))).toByte)
        gate(Side("passes rendered and mixed in the JVM", jvmMix),
          Side("2-pass renderMix", () => { val _ = native.renderMix(STANDARD_IMAGE_SIZE, ids, passes) }),
          MAX_SLOWDOWN_RENDER_MIX)

  "A custom-geometry instance" should "render within its slowdown of a built-in sphere" taggedAs Perf in:
    Using.resource(newRenderer()): builtIn =>
      Using.resource(newRenderer()): custom =>
        builtIn.addSphereInstance(at(0f, 0f, 0f, 0.5f), red)
        val typeId = custom.registerCustomGeometry(
          CustomGeometryStub.ptx(), "__intersection__spi_stub", "__closesthit__spi_stub")
        custom.addCustomGeometryInstance(typeId, Array(-0.6f, -0.6f, -0.6f), Array(0.6f, 0.6f, 0.6f),
          Array(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f)).value should be >= 0
        gate(Side("built-in sphere", frame(builtIn)), Side("SPI stub instance", frame(custom)),
          MAX_SLOWDOWN_CUSTOM_GEOMETRY)

  "Caustics" should "stay within their slowdown of the same scene without them" taggedAs Perf in:
    def glassScene(r: OptiXRenderer): Unit =
      r.addSphereInstance(at(0f, 0f, 0f, 0.6f), glass)
      r.addPlane(1, positive = true, -1f)
    Using.resource(newRenderer()): plain =>
      Using.resource(newRenderer()): caustic =>
        glassScene(plain)
        glassScene(caustic)
        caustic.enableCaustics(photonsPerIter = 50000, iterations = 2)
        gate(Side("no caustics", frame(plain)), Side("caustics", frame(caustic)),
          MAX_SLOWDOWN_CAUSTICS)

/** The SPI test stub's PTX (`spi_test_stub.ptx`, built with the library). */
private[optix] object CustomGeometryStub:
  def ptx(): Array[Byte] =
    Option(getClass.getResourceAsStream("/native/x86_64-linux/spi_test_stub.ptx")) match
      case Some(stream) => try stream.readAllBytes() finally stream.close()
      case None => java.nio.file.Files.readAllBytes(
        java.nio.file.Paths.get("target/native/x86_64-linux/bin/spi_test_stub.ptx"))
