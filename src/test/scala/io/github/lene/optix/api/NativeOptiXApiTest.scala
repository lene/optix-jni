package io.github.lene.optix.api

import java.util.Optional

import scala.util.Try
import scala.util.Using

import io.github.lene.optix.OptiXRenderer
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class NativeOptiXApiTest extends AnyFlatSpec with Matchers:

  private val api = NativeOptiXApi.api

  private def gpuAvailable: Boolean =
    OptiXRenderer.isLibraryLoaded && Try(api.createContext().close()).isSuccess

  private def ptx: Array[Byte] =
    val stream = getClass.getResourceAsStream("/native/x86_64-linux/optix_shaders.ptx")
    assume(stream != null, "PTX resource must be available") // scalafix:ok DisableSyntax.null
    try stream.readAllBytes() finally stream.close()

  "NativeOptiXApi" should "be accessible without throwing" in {
    // No GPU needed: just verify JNI linking is correct
    noException should be thrownBy NativeOptiXApi.api
  }

  it should "reject null optional guide containers before JNI" in {
    assume(gpuAvailable, "GPU/OptiX required")
    Using.resource(api.createContext()): ctx =>
      val denoiser = api.createDenoiser(ctx, guideAlbedo = false, guideNormal = false)
      an[IllegalArgumentException] should be thrownBy
        api.denoiseFloat4(
          denoiser,
          1,
          1,
          Array.fill(4)(0.0f),
          null, // scalafix:ok DisableSyntax.null
          Optional.empty()
        )
  }

  it should "create and close a context" in {
    assume(gpuAvailable, "GPU/OptiX required")
    val ctx = api.createContext()
    ctx.isOpen shouldBe true
    ctx.close()
    ctx.isOpen shouldBe false
  }

  "A handle" should "do nothing when closed twice (optix-jni#72: a second destroy freed twice)" in {
    assume(gpuAvailable, "GPU/OptiX required")
    val ctx = api.createContext()
    val module = api.createModuleFromPTX(ctx, ptx)
    module.close()
    noException should be thrownBy module.close()
    ctx.close()
    noException should be thrownBy ctx.close()
  }

  it should "refuse use after close instead of passing a freed handle to native code" in {
    assume(gpuAvailable, "GPU/OptiX required")
    val ctx = api.createContext()
    val module = api.createModuleFromPTX(ctx, ptx)
    module.close()
    an[IllegalStateException] should be thrownBy api.createRaygenGroup(ctx, module, "__raygen__rg")
    ctx.close()
    an[IllegalStateException] should be thrownBy api.createModuleFromPTX(ctx, ptx)
  }

  it should "be closed, child before context, when its context is closed" in {
    assume(gpuAvailable, "GPU/OptiX required")
    val ctx = api.createContext()
    val module = api.createModuleFromPTX(ctx, ptx)
    val rg = api.createRaygenGroup(ctx, module, "__raygen__rg")
    val denoiser = api.createDenoiser(ctx, guideAlbedo = false, guideNormal = false)
    ctx.close()
    Seq(module.isOpen, rg.isOpen, denoiser.isOpen, ctx.isOpen) shouldBe Seq(false, false, false, false)
    noException should be thrownBy rg.close()
  }

  it should "not be accepted by another context" in {
    assume(gpuAvailable, "GPU/OptiX required")
    Using.resources(api.createContext(), api.createContext()): (a, b) =>
      val module = api.createModuleFromPTX(a, ptx)
      an[IllegalArgumentException] should be thrownBy api.createRaygenGroup(b, module, "__raygen__rg")
  }

  "A failed creation" should "throw OptiXException instead of returning a 0 handle" in {
    assume(gpuAvailable, "GPU/OptiX required")
    Using.resource(api.createContext()): ctx =>
      an[io.github.lene.optix.OptiXException] should be thrownBy
        api.createModuleFromPTX(ctx, "not PTX".getBytes("UTF-8"))
      val module = api.createModuleFromPTX(ctx, ptx)
      an[io.github.lene.optix.OptiXException] should be thrownBy
        api.createRaygenGroup(ctx, module, "__raygen__does_not_exist")
  }

  "NativeOptiXApi" should "create a module from PTX bytes" in {
    assume(gpuAvailable, "GPU/OptiX required")
    Using.resource(api.createContext()): ctx =>
      api.createModuleFromPTX(ctx, ptx).isOpen shouldBe true
  }

  it should "create raygen, miss, hit program groups from a module" in {
    assume(gpuAvailable, "GPU/OptiX required")
    Using.resource(api.createContext()): ctx =>
      val module = api.createModuleFromPTX(ctx, ptx)
      val groups = Seq(
        api.createRaygenGroup(ctx, module, "__raygen__rg"),
        api.createMissGroup(ctx, module, "__miss__ms"),
        // Triangle hitgroup (built-in IS, no custom intersection shader needed)
        api.createTriangleHitGroup(ctx, module, "__closesthit__ch"))
      groups.forall(_.isOpen) shouldBe true
      groups.reverse.foreach(_.close())
  }

  it should "create a pipeline from program groups" in {
    assume(gpuAvailable, "GPU/OptiX required")
    Using.resource(api.createContext()): ctx =>
      val module = api.createModuleFromPTX(ctx, ptx)
      val rg     = api.createRaygenGroup(ctx, module, "__raygen__rg")
      val miss   = api.createMissGroup(ctx, module, "__miss__ms")
      val hit    = api.createTriangleHitGroup(ctx, module, "__closesthit__ch")
      api.createPipeline(ctx, Array(rg, miss, hit), maxTraceDepth = 2).isOpen shouldBe true
  }

  it should "create a curve hitgroup and pipeline when GPU is available" in {
    assume(gpuAvailable, "GPU/OptiX required")
    Using.resource(api.createContext()): ctx =>
      val module = api.createModuleFromPTX(ctx, ptx)
      val rg     = api.createRaygenGroup(ctx, module, "__raygen__rg")
      val miss   = api.createMissGroup(ctx, module, "__miss__ms")
      val curve  = api.createCurveHitGroup(ctx, module, "__closesthit__curve")
      api.createPipeline(ctx, Array(rg, miss, curve), maxTraceDepth = 2).isOpen shouldBe true
  }

  it should "create and close denoisers" in {
    assume(gpuAvailable, "GPU/OptiX required")
    Using.resource(api.createContext()): ctx =>
      val denoiser = api.createDenoiser(ctx, guideAlbedo = false, guideNormal = false)
      denoiser.close()
      denoiser.isOpen shouldBe false
      api.createDenoiser(ctx, guideAlbedo = true, guideNormal = true).isOpen shouldBe true
  }

  it should "denoise a synthetic float4 image" in {
    assume(gpuAvailable, "GPU/OptiX required")
    Using.resource(api.createContext()): ctx =>
      val denoiser = api.createDenoiser(ctx, guideAlbedo = false, guideNormal = false)
      val width = 16
      val height = 16
      val color = Array.tabulate(width * height * 4) { index =>
        val pixel = index / 4
        index % 4 match
          case 0 => if pixel % 2 == 0 then 0.9f else 0.2f
          case 1 => 0.4f
          case 2 => if pixel % 3 == 0 then 0.1f else 0.8f
          case _ => 1.0f
      }
      val output = api.denoiseFloat4(denoiser, width, height, color)
      output.length shouldBe color.length
      output.forall(java.lang.Float.isFinite) shouldBe true
      output.grouped(4).forall(_(3) == 1.0f) shouldBe true
      output.exists(_ != 0.0f) shouldBe true
  }

  it should "throw OptiXException for a rejected denoise input instead of an empty array" in {
    assume(gpuAvailable, "GPU/OptiX required")
    Using.resource(api.createContext()): ctx =>
      val denoiser = api.createDenoiser(ctx, guideAlbedo = false, guideNormal = false)
      an[io.github.lene.optix.OptiXException] should be thrownBy
        api.denoiseFloat4(denoiser, 16, 16, Array.fill(4)(0.5f))
  }
