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

  "NativeOptiXApi.createContext" should "give a context or throw OptiXException, without a GPU too" in:
    // optix-jni#72: it returned 0L for "no GPU", which every caller had to check by hand.
    assumeNativeLibraryLoaded()
    scala.util.Try(api.createContext()) match
      case scala.util.Success(context) => noException should be thrownBy context.close()
      case scala.util.Failure(e) => e shouldBe an[io.github.lene.optix.OptiXException]

  "OptiXRenderer initialization" should "return the same result when called twice" in:
    assumeNativeLibraryLoaded()
    val renderer = new OptiXRenderer()
    try
      val first = renderer.initialize()
      val second = renderer.initialize()
      second shouldBe first
    finally
      renderer.dispose()
