package io.github.lene.optix.api

import java.util.Optional

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import io.github.lene.optix.OptiXRenderer

class NativeOptiXApiTest extends AnyFlatSpec with Matchers:

  private val api = NativeOptiXApi.api

  private def gpuAvailable: Boolean =
    OptiXRenderer.isLibraryLoaded && {
      val ctx = api.createContext()
      if (ctx != 0L) { api.destroyContext(ctx); true }
      else false
    }

  "NativeOptiXApi" should "be accessible without throwing" in {
    // No GPU needed: just verify JNI linking is correct
    noException should be thrownBy NativeOptiXApi.api
  }

  it should "create a context when GPU is available" in {
    assume(gpuAvailable, "GPU/OptiX required")
    val ctx = api.createContext()
    ctx should not be 0L
    api.destroyContext(ctx)
  }

  it should "handle destroyContext(0L) safely" in {
    noException should be thrownBy api.destroyContext(0L)
  }

  it should "handle destroyModule with null handles safely" in {
    noException should be thrownBy api.destroyModule(0L, 0L)
  }

  it should "handle destroyProgramGroup with null handles safely" in {
    noException should be thrownBy api.destroyProgramGroup(0L, 0L)
  }

  it should "handle destroyPipeline with null handles safely" in {
    noException should be thrownBy api.destroyPipeline(0L, 0L)
  }

  it should "handle destroyDenoiser with a null handle safely" in {
    noException should be thrownBy api.destroyDenoiser(0L)
  }

  it should "reject null optional guide containers before JNI" in {
    an[IllegalArgumentException] should be thrownBy
      api.denoiseFloat4(
        0L,
        1,
        1,
        Array.fill(4)(0.0f),
        null, // scalafix:ok DisableSyntax.null
        Optional.empty()
      )
  }

  it should "create and destroy a context" in {
    assume(gpuAvailable, "GPU/OptiX required")
    val ctx = api.createContext()
    ctx should not be 0L
    api.destroyContext(ctx)
  }

  it should "create a module from PTX bytes" in {
    assume(gpuAvailable, "GPU/OptiX required")
    val ctx = api.createContext()
    ctx should not be 0L

    // Load the bundled optix PTX shader
    val ptxStream = getClass.getResourceAsStream("/native/x86_64-linux/optix_shaders.ptx")
    assume(ptxStream != null, "PTX resource must be available")
    val ptxBytes = ptxStream.readAllBytes()
    ptxStream.close()

    val module = api.createModuleFromPTX(ctx, ptxBytes)
    module should not be 0L
    api.destroyModule(ctx, module)
    api.destroyContext(ctx)
  }

  it should "create raygen, miss, hit program groups from a module" in {
    assume(gpuAvailable, "GPU/OptiX required")
    val ctx = api.createContext()
    ctx should not be 0L

    val ptxStream = getClass.getResourceAsStream("/native/x86_64-linux/optix_shaders.ptx")
    assume(ptxStream != null, "PTX resource must be available")
    val ptxBytes = ptxStream.readAllBytes()
    ptxStream.close()

    val module = api.createModuleFromPTX(ctx, ptxBytes)
    module should not be 0L

    val rg   = api.createRaygenGroup(ctx, module, "__raygen__rg")
    val miss = api.createMissGroup(ctx, module, "__miss__ms")
    // Triangle hitgroup (built-in IS, no custom intersection shader needed)
    val hit  = api.createTriangleHitGroup(ctx, module, "__closesthit__ch")

    rg   should not be 0L
    miss should not be 0L
    hit  should not be 0L

    api.destroyProgramGroup(ctx, hit)
    api.destroyProgramGroup(ctx, miss)
    api.destroyProgramGroup(ctx, rg)
    api.destroyModule(ctx, module)
    api.destroyContext(ctx)
  }

  it should "create a pipeline from program groups" in {
    assume(gpuAvailable, "GPU/OptiX required")
    val ctx = api.createContext()
    ctx should not be 0L

    val ptxStream = getClass.getResourceAsStream("/native/x86_64-linux/optix_shaders.ptx")
    assume(ptxStream != null, "PTX resource must be available")
    val ptxBytes = ptxStream.readAllBytes()
    ptxStream.close()

    val module = api.createModuleFromPTX(ctx, ptxBytes)
    val rg     = api.createRaygenGroup(ctx, module, "__raygen__rg")
    val miss   = api.createMissGroup(ctx, module, "__miss__ms")
    val hit    = api.createTriangleHitGroup(ctx, module, "__closesthit__ch")

    val pipeline = api.createPipeline(ctx, Array(rg, miss, hit), maxTraceDepth = 2)
    pipeline should not be 0L

    api.destroyPipeline(ctx, pipeline)
    api.destroyProgramGroup(ctx, hit)
    api.destroyProgramGroup(ctx, miss)
    api.destroyProgramGroup(ctx, rg)
    api.destroyModule(ctx, module)
    api.destroyContext(ctx)
  }

  it should "create a curve hitgroup and pipeline when GPU is available" in {
    assume(gpuAvailable, "GPU/OptiX required")
    val ctx = api.createContext()
    ctx should not be 0L

    val ptxStream = getClass.getResourceAsStream("/native/x86_64-linux/optix_shaders.ptx")
    assume(ptxStream != null, "PTX resource must be available")
    val ptxBytes = ptxStream.readAllBytes()
    ptxStream.close()

    val module = api.createModuleFromPTX(ctx, ptxBytes)
    val rg     = api.createRaygenGroup(ctx, module, "__raygen__rg")
    val miss   = api.createMissGroup(ctx, module, "__miss__ms")
    val curve  = api.createCurveHitGroup(ctx, module, "__closesthit__curve")

    rg should not be 0L
    miss should not be 0L
    curve should not be 0L

    val pipeline = api.createPipeline(ctx, Array(rg, miss, curve), maxTraceDepth = 2)
    pipeline should not be 0L

    api.destroyPipeline(ctx, pipeline)
    api.destroyProgramGroup(ctx, curve)
    api.destroyProgramGroup(ctx, miss)
    api.destroyProgramGroup(ctx, rg)
    api.destroyModule(ctx, module)
    api.destroyContext(ctx)
  }

  it should "create and destroy denoisers" in {
    assume(gpuAvailable, "GPU/OptiX required")
    val ctx = api.createContext()
    ctx should not be 0L

    val denoiser = api.createDenoiser(ctx, guideAlbedo = false, guideNormal = false)
    denoiser should not be 0L
    api.destroyDenoiser(denoiser)

    val guidedDenoiser = api.createDenoiser(ctx, guideAlbedo = true, guideNormal = true)
    guidedDenoiser should not be 0L
    api.destroyDenoiser(guidedDenoiser)

    api.destroyContext(ctx)
  }

  it should "denoise a synthetic float4 image" in {
    assume(gpuAvailable, "GPU/OptiX required")
    val ctx = api.createContext()
    ctx should not be 0L

    val denoiser = api.createDenoiser(ctx, guideAlbedo = false, guideNormal = false)
    denoiser should not be 0L

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

    val output = api.denoiseFloat4(
      denoiser,
      width,
      height,
      color
    )
    output.length shouldBe color.length
    output.forall(java.lang.Float.isFinite) shouldBe true
    output.grouped(4).forall(_(3) == 1.0f) shouldBe true
    output.exists(_ != 0.0f) shouldBe true

    api.destroyDenoiser(denoiser)
    api.destroyContext(ctx)
  }
