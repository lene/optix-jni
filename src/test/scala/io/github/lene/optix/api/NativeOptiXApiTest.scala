package io.github.lene.optix.api

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
