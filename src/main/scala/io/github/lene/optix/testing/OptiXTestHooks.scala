package io.github.lene.optix.testing

import io.github.lene.optix.OptiXRenderer

/** IAS builds since initialization: full rebuilds and in-place updates (optix-jni#66). */
final case class IasUpdateCounts(fullBuilds: Long, inPlaceUpdates: Long)

/** Fault injection and counters for the tests of code built on optix-jni (optix-jni#76): a
  * caller's test reaches them here instead of declaring classes in `io.github.lene.optix`,
  * whose `private[optix]` members are internal. Not for production use.
  */
object OptiXTestHooks:

  /** The renderer's next `setProjectedMesh` fails as if its GPU readback had failed (-2). */
  def failNextProjectionReadback(renderer: OptiXRenderer): Unit =
    renderer.failNextProjectionReadbackNative()

  /** Latches the process-wide context-lost state, as a sticky CUDA error would (optix-jni#73).
    * Every later render throws `OptiXContextLostException` until [[clearContextLost]].
    */
  def simulateContextLost(renderer: OptiXRenderer, reason: String): Unit =
    renderer.simulateContextLostNative(reason)

  /** Clears the process-wide context-lost latch. Only a test can recover in-process. */
  def clearContextLost(renderer: OptiXRenderer): Unit =
    renderer.clearContextLostNative()

  /** Full IAS builds and in-place IAS updates since `initialize`.
    *
    * @throws IllegalStateException when the renderer is not initialized
    */
  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  def iasUpdateCounts(renderer: OptiXRenderer): IasUpdateCounts =
    Option(renderer.iasUpdateCountsNative()) match
      case Some(Array(full, inPlace)) => IasUpdateCounts(full, inPlace)
      case _ => throw IllegalStateException("iasUpdateCounts: the renderer is not initialized")
