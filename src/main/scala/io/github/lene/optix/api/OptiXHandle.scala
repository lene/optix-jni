package io.github.lene.optix.api

import java.lang.ref.Cleaner
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/** One native resource of the low-level API (optix-jni#72). It replaces a raw `Long` handle,
  * so a destroyed handle can no longer be used, freed twice, or passed to the wrong call.
  *
  * Closing it frees the resource; closing it again does nothing. A handle that is never
  * closed is freed by a [[java.lang.ref.Cleaner]] once it is unreachable, but close it
  * explicitly (`scala.util.Using`, try-with-resources): the GPU memory it holds is not visible
  * to the JVM's garbage collector.
  *
  * Ownership: a [[ContextHandle]] owns every handle created from it. A child keeps its context
  * alive, and closing the context closes its open children first. So the native destroy order
  * is always child before context.
  */
sealed abstract class OptiXHandle private[api] (raw: Long, release: Long => Unit)
    extends AutoCloseable:
  private val cell = new AtomicLong(raw)
  private[api] val cleanable: Cleaner.Cleanable =
    OptiXHandle.cleaner.register(this, OptiXHandle.Release(cell, release))

  /** The native handle.
    *
    * @throws IllegalStateException once the handle is closed
    */
  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  private[api] def native: Long =
    val handle = cell.get
    if handle == 0L then throw IllegalStateException(s"${getClass.getSimpleName} is closed")
    handle

  /** `true` until the handle is closed, by [[close]] or by closing its context. */
  def isOpen: Boolean = cell.get != 0L

  /** Frees the native resource; does nothing when it is already freed. */
  override def close(): Unit = cleanable.clean()

object OptiXHandle:
  private val cleaner: Cleaner = Cleaner.create()

  // Must not reference the handle itself, or the handle would never become unreachable.
  private final class Release(cell: AtomicLong, release: Long => Unit) extends Runnable:
    override def run(): Unit =
      val handle = cell.getAndSet(0L)
      if handle != 0L then release(handle)

/** An OptiX device context, created by [[NativeOptiXApi.createContext]]. */
final class ContextHandle private[api] (
    raw: Long,
    // ponytail: closed children's cleanables stay queued until the context closes; a context
    // creating millions of modules would want them removed on close.
    owned: ConcurrentLinkedQueue[Cleaner.Cleanable]
) extends OptiXHandle(raw, handle =>
      owned.forEach(_.clean())
      NativeOptiXApi.api.destroyContextNative(handle)
    ):
  private[api] def adopt[H <: ContextChild](child: H): H =
    owned.add(child.cleanable)
    child

/** A handle created from a [[ContextHandle]]; it keeps that context alive. */
sealed abstract class ContextChild private[api] (
    val context: ContextHandle,
    raw: Long,
    destroy: (Long, Long) => Unit
) extends OptiXHandle(raw, {
      val contextNative = context.native
      handle => destroy(contextNative, handle)
    })

/** An OptiX module compiled from PTX, by [[NativeOptiXApi.createModuleFromPTX]]. */
final class ModuleHandle private[api] (context: ContextHandle, raw: Long)
    extends ContextChild(context, raw, NativeOptiXApi.api.destroyModuleNative)

/** A program group (raygen, miss or hit group) created from a [[ModuleHandle]]. */
final class ProgramGroupHandle private[api] (context: ContextHandle, raw: Long)
    extends ContextChild(context, raw, NativeOptiXApi.api.destroyProgramGroupNative)

/** A pipeline linked from program groups, by [[NativeOptiXApi.createPipeline]]. */
final class PipelineHandle private[api] (context: ContextHandle, raw: Long)
    extends ContextChild(context, raw, NativeOptiXApi.api.destroyPipelineNative)

/** An HDR denoiser, by [[NativeOptiXApi.createDenoiser]]. */
final class DenoiserHandle private[api] (context: ContextHandle, raw: Long)
    extends ContextChild(context, raw, (_, handle) => NativeOptiXApi.api.destroyDenoiserNative(handle))
