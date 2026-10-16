package io.github.lene.optix

import java.lang.reflect.InvocationTargetException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Hostile input at the native entries (review 2026-10-14, optix-jni#68-#71): sizes that wrapped
  * in 32 bits, null and short arrays, out-of-range counts, indices and ids, and a dispose racing
  * a render. Each used to read or write past a buffer, abort the JVM or hang the GPU; each must
  * now be a Java exception (or, for the race, no crash). The natives are called directly --
  * through reflection where they are private -- because the Scala wrappers check some of this
  * first and the native entry is the boundary another JVM caller reaches.
  */
class JniBoundarySuite extends AnyFlatSpec with Matchers with RendererFixture:

  private val transform: Array[Float] = Array(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f)
  private val material: Array[Float] = Array.fill(12)(0.5f)

  /** Calls a (possibly private) native by name and returns what it threw, if anything. */
  private def callNative(name: String, args: AnyRef*): Option[Throwable] =
    val method = classOf[OptiXRenderer].getDeclaredMethods.find(m =>
      // native only: setLight and others have public overloads of the same arity
      m.getName == name && m.getParameterCount == args.length &&
        java.lang.reflect.Modifier.isNative(m.getModifiers)
    ).getOrElse(fail(s"no native $name/${args.length}"))
    method.setAccessible(true)
    try
      method.invoke(renderer, args*)
      None
    catch case e: InvocationTargetException => Some(e.getCause)

  private def int(i: Int): Integer = Integer.valueOf(i)
  private def float(f: Float): java.lang.Float = java.lang.Float.valueOf(f)
  private def bool(b: Boolean): java.lang.Boolean = java.lang.Boolean.valueOf(b)
  private val nil: AnyRef = null // scalafix:ok DisableSyntax.null

  private def rejects[T <: Throwable](name: String, args: AnyRef*)(using
      ct: scala.reflect.ClassTag[T]): Unit =
    val thrown = callNative(name, args*)
    withClue(s"$name(${args.mkString(", ")}): "):
      thrown.map(_.getClass) shouldBe Some(ct.runtimeClass)

  private def accepts(name: String, args: AnyRef*): Unit =
    withClue(s"$name(${args.mkString(", ")}): "):
      callNative(name, args*) shouldBe None

  // ---- #68: 32-bit size overflow ----

  "A texture whose size wraps in 32 bits" should "be rejected, not read 4 GiB from 128 KiB" in:
    // 32768 * 32769 * 4 wrapped to 131072, so this array passed the old length check.
    rejects[IllegalArgumentException]("uploadTextureNative", "t", new Array[Byte](131072),
      int(32768), int(32769))
    rejects[IllegalArgumentException]("updateTextureNative", int(0), new Array[Byte](131072),
      int(32768), int(32769))
    an[IllegalArgumentException] should be thrownBy
      renderer.uploadTexture("t", new Array[Byte](131072), 32768, 32769)

  "A render of 2^30 pixels" should "be rejected, not allocate a 0-byte image" in:
    an[IllegalArgumentException] should be thrownBy renderer.renderWithStats(32768, 32768)

  // ---- #69: null and short arrays ----

  "A null array or string" should "throw NullPointerException at every entry that takes one" in:
    rejects[NullPointerException]("setLight", nil, float(1f))
    rejects[NullPointerException]("setLights", nil)
    rejects[NullPointerException]("setTriangleMeshNative", nil, int(3), Array(0, 1, 2), int(1),
      int(6))
    rejects[NullPointerException]("setTriangleMeshNative", new Array[Float](18), int(3), nil,
      int(1), int(6))
    rejects[NullPointerException]("updateCpuTriangleMeshNative", int(0), nil, int(3),
      Array(0, 1, 2), int(1), int(6))
    rejects[NullPointerException]("addSphereInstanceNative", nil, material)
    rejects[NullPointerException]("addTriangleMeshInstanceNative", nil, material, int(-1))
    rejects[NullPointerException]("addRecursiveIASSpongeInstanceNative", int(1), nil, material,
      int(-1))
    rejects[NullPointerException]("registerCustomGeometryNative", nil, "is", "ch", nil, nil, nil)
    rejects[NullPointerException]("registerCustomGeometryNative", new Array[Byte](4), nil, "ch",
      nil, nil, nil)
    rejects[NullPointerException]("addCustomGeometryInstanceNative", int(0), nil,
      new Array[Float](3), transform, nil)
    rejects[NullPointerException]("addCurveInstanceNative", nil, new Array[Float](4), int(4),
      material)
    rejects[NullPointerException]("uploadTextureFromFileNative", nil)
    rejects[NullPointerException]("uploadTextureNative", nil, new Array[Byte](4), int(1), int(1))

  "A short array" should "be rejected before it is read past its end" in:
    rejects[IllegalArgumentException]("setLight", Array(1f), float(1f))
    rejects[IllegalArgumentException]("updateCpuTriangleMeshNative", int(0), new Array[Float](6),
      int(3), Array(0, 1, 2), int(1), int(6))
    rejects[IllegalArgumentException]("addSphereInstanceNative", new Array[Float](3), material)
    rejects[IllegalArgumentException]("addCurveInstanceNative", new Array[Float](3),
      new Array[Float](4), int(4), material)

  // ---- #70: ranges ----

  "A triangle index outside the vertex range" should "be rejected, not read past the buffer" in:
    rejects[IllegalArgumentException]("setTriangleMeshNative", new Array[Float](18), int(3),
      Array(0, 1, 3), int(1), int(6))
    rejects[IllegalArgumentException]("setTriangleMeshNative", new Array[Float](18), int(3),
      Array(0, -1, 2), int(1), int(6))
    rejects[IllegalArgumentException]("updateCpuTriangleMeshNative", int(0), new Array[Float](18),
      int(3), Array(0, 1, 3), int(1), int(6))
    rejects[IllegalArgumentException]("updateCpuTriangleMeshNative", int(0), new Array[Float](21),
      int(3), Array(0, 1, 2), int(1), int(7))

  "An unregistered custom-geometry typeId" should "be rejected, not index past the SBT" in:
    val box = new Array[Float](3)
    rejects[IllegalArgumentException]("addCustomGeometryInstanceNative", int(0), box, box,
      transform, nil) // a built-in type, not a custom one
    rejects[IllegalArgumentException]("addCustomGeometryInstanceNative", int(9999), box, box,
      transform, nil)

  "Render settings out of range" should "be rejected" in:
    rejects[IllegalArgumentException]("setMaxRayDepth", int(0))
    rejects[IllegalArgumentException]("setMaxRayDepth", int(1000))
    rejects[IllegalArgumentException]("setAccumulationFramesNative", int(0))
    rejects[IllegalArgumentException]("setAccumulationFramesNative", int(Int.MaxValue))
    rejects[IllegalArgumentException]("setIBLNative", bool(true), float(1f), int(0))
    rejects[IllegalArgumentException]("setIBLNative", bool(true), float(1f), int(1000000))
    rejects[IllegalArgumentException]("setAntialiasing", bool(true), int(5), float(0.1f))
    rejects[IllegalArgumentException]("setAntialiasing", bool(true), int(2), float(Float.NaN))
    rejects[IllegalArgumentException]("setCaustics", bool(true), int(0), int(10), float(1f),
      float(0.7f))
    rejects[IllegalArgumentException]("setCaustics", bool(true), int(1000), int(Int.MaxValue),
      float(1f), float(0.7f))
    rejects[IllegalArgumentException]("addPlaneNative", int(3), bool(true), float(0f))
    rejects[IllegalArgumentException]("addPlaneSolidColorNative", int(-1), bool(true), float(0f),
      float(1f), float(1f), float(1f))

  they should "still accept the values callers pass" in:
    accepts("setMaxRayDepth", int(1))
    accepts("setMaxRayDepth", int(5))
    accepts("setAccumulationFramesNative", int(1))
    accepts("setIBLNative", bool(false), float(0f), int(0)) // disabled: samples unused
    accepts("setIBLNative", bool(true), float(1f), int(8))
    accepts("setAntialiasing", bool(false), int(0), float(0f))
    accepts("setAntialiasing", bool(true), int(4), float(1f))
    accepts("setCaustics", bool(false), int(0), int(0), float(0f), float(0f))
    accepts("addPlaneNative", int(2), bool(true), float(0f))

  // ---- #71: dispose during render ----

  "Disposing while another thread renders" should "wait for the render, not free it under it" in:
    val r = new OptiXRenderer()
    r.initialize() shouldBe true
    r.setCamera(menger.common.Vector[3](0f, 0f, 3f), menger.common.Vector[3](0f, 0f, 0f),
      menger.common.Vector[3](0f, 1f, 0f), 60f)
    r.addSphereInstance(menger.common.Vector[3](0f, 0f, 0f),
      Material(menger.common.Color(1f, 0f, 0f, 1f), 1.0f))
    val started = new java.util.concurrent.CountDownLatch(1)
    val disposed = new AtomicBoolean(false)
    val failure = new AtomicReference[Throwable]()
    val loop: Runnable = () =>
      try
        Iterator.continually(disposed.get()).takeWhile(!_).foreach: _ =>
          r.renderWithStats(320, 240)
          started.countDown()
        // After dispose every call sees a null handle and returns "not ready".
        if Option(r.renderWithStats(320, 240)).isDefined then
          failure.set(new AssertionError("render after dispose returned a result"))
      catch case t: Throwable => failure.set(t)
    val renderLoop = new Thread(loop)
    renderLoop.start()
    started.await()
    Thread.sleep(15) // let a few more renders start, so dispose lands mid-render
    r.dispose()
    disposed.set(true)
    renderLoop.join(60000)
    renderLoop.isAlive shouldBe false
    Option(failure.get()).foreach(t => fail(s"render loop failed: $t", t))
