package io.github.lene.optix.api

import io.github.lene.optix.OptiXRenderer
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class NativeOptiXNoGpuSuite extends AnyFlatSpec with Matchers:

  private val api = NativeOptiXApi.api
  private val nativeLibraryMessage = "OptiX native library not loaded"

  private def assumeNativeLibraryLoaded(): Unit =
    assume(OptiXRenderer.isLibraryLoaded, nativeLibraryMessage)

  "OptiX native library loading" should "return a non-exception boolean result" in:
    noException should be thrownBy
      OptiXRenderer.isLibraryLoaded
    assumeNativeLibraryLoaded()
    OptiXRenderer.isLibraryLoaded shouldBe true

  "NativeOptiXApi zero handles" should "be safe to destroy without a GPU context" in:
    assumeNativeLibraryLoaded()
    noException should be thrownBy
      api.destroyContext(0L)
    noException should be thrownBy
      api.destroyModule(0L, 0L)
    noException should be thrownBy
      api.destroyPipeline(0L, 0L)
    noException should be thrownBy
      api.destroyDenoiser(0L)

  it should "handle createContext returning no context without requiring a GPU" in:
    assumeNativeLibraryLoaded()
    val context = api.createContext()
    if context == 0L then
      succeed
    else
      noException should be thrownBy
        api.destroyContext(context)

  "OptiXRenderer initialization" should "return the same result when called twice" in:
    assumeNativeLibraryLoaded()
    val renderer = new OptiXRenderer()
    try
      val first = renderer.initialize()
      val second = renderer.initialize()
      second shouldBe first
    finally
      renderer.dispose()
