package io.github.lene.optix.api

import java.util.Optional
import java.util.concurrent.ConcurrentLinkedQueue

import io.github.lene.optix.OptiXException

/** Thin JNI bindings for selected OptiX 7.x context operations.
 *
 *  Each method wraps one C++ `OptiXContext` operation. Resources are typed handles
 *  ([[OptiXHandle]]): close one to free it; a context closes the handles created from it first
 *  (optix-jni#72: these were raw `Long`s, and a second destroy was a use-after-free). A failed
 *  creation throws [[io.github.lene.optix.OptiXException]]; entry point names must match
 *  symbols compiled into the supplied PTX.
 *
 *  Usage:
 *  {{{
 *    val api = NativeOptiXApi.api
 *    Using.resource(api.createContext()): ctx =>   // throws when GPU/OptiX is not available
 *      val module   = api.createModuleFromPTX(ctx, ptxBytes)
 *      val rg       = api.createRaygenGroup(ctx, module, "__raygen__main")
 *      val miss     = api.createMissGroup(ctx, module, "__miss__main")
 *      val hit      = api.createHitGroup(ctx, module, "__closesthit__main", "__intersection__main")
 *      val pipeline = api.createPipeline(ctx, Array(rg, miss, hit), maxTraceDepth = 2)
 *      // ... render ...
 *    // closing the context closed pipeline, groups and module, in that order
 *  }}}
 *
 *  Thread affinity: a context and its handles may be used from any one thread at a time; the
 *  API does no locking of its own.
 */
class NativeOptiXApi:

  // ---- Context lifecycle ----

  /** Creates and initializes a new OptiX device context.
    *
    * @throws io.github.lene.optix.OptiXException when CUDA or OptiX initialization fails
    */
  def createContext(): ContextHandle =
    ContextHandle(created("createContext", createContextNative()), ConcurrentLinkedQueue())

  // ---- Module lifecycle ----

  /** Creates an OptiX module from PTX bytecode.
    *
    * @param context owning context
    * @param ptxBytes UTF-8 PTX source bytes
    * @throws io.github.lene.optix.OptiXException when compilation fails
    */
  def createModuleFromPTX(context: ContextHandle, ptxBytes: Array[Byte]): ModuleHandle =
    context.adopt(ModuleHandle(context,
      created("createModuleFromPTX", createModuleFromPTXNative(context.native, ptxBytes))))

  // ---- Program group lifecycle ----

  /** Creates a ray-generation program group from a module entry point.
    *
    * @throws io.github.lene.optix.OptiXException on failure
    */
  def createRaygenGroup(context: ContextHandle, module: ModuleHandle, entryPoint: String): ProgramGroupHandle =
    group(context, module, "createRaygenGroup",
      createRaygenGroupNative(context.native, module.native, entryPoint))

  /** Creates a miss program group from a module entry point.
    *
    * @throws io.github.lene.optix.OptiXException on failure
    */
  def createMissGroup(context: ContextHandle, module: ModuleHandle, entryPoint: String): ProgramGroupHandle =
    group(context, module, "createMissGroup",
      createMissGroupNative(context.native, module.native, entryPoint))

  /** Creates a custom-primitive hitgroup.
    *
    * `closestHitEntry` and `isEntry` name closest-hit and intersection symbols
    * in the module.
    *
    * @throws io.github.lene.optix.OptiXException on failure
    */
  def createHitGroup(
    context: ContextHandle,
    module: ModuleHandle,
    closestHitEntry: String,
    isEntry: String
  ): ProgramGroupHandle =
    group(context, module, "createHitGroup",
      createHitGroupNative(context.native, module.native, closestHitEntry, isEntry))

  /** Creates a triangle-geometry hitgroup using OptiX built-in intersection.
    *
    * @throws io.github.lene.optix.OptiXException on failure
    */
  def createTriangleHitGroup(
    context: ContextHandle,
    module: ModuleHandle,
    closestHitEntry: String
  ): ProgramGroupHandle =
    group(context, module, "createTriangleHitGroup",
      createTriangleHitGroupNative(context.native, module.native, closestHitEntry))

  /** Creates a cubic B-spline curve hitgroup using OptiX built-in intersection.
    *
    * The returned handle owns both the hitgroup and its hidden built-in
    * intersection module; closing it frees both.
    *
    * @throws io.github.lene.optix.OptiXException on failure
    */
  def createCurveHitGroup(
    context: ContextHandle,
    module: ModuleHandle,
    closestHitEntry: String
  ): ProgramGroupHandle =
    group(context, module, "createCurveHitGroup",
      createCurveHitGroupNative(context.native, module.native, closestHitEntry))

  // ---- Pipeline lifecycle ----

  /** Creates a pipeline from program groups.
    *
    * @param groups raygen, miss, and hitgroup handles from this context
    * @param maxTraceDepth maximum ray recursion depth
    * @throws io.github.lene.optix.OptiXException on failure
    */
  def createPipeline(
    context: ContextHandle,
    groups: Array[ProgramGroupHandle],
    maxTraceDepth: Int
  ): PipelineHandle =
    groups.foreach(g => requireSameContext(context, g))
    context.adopt(PipelineHandle(context, created("createPipeline",
      createPipelineNative(context.native, groups.map(_.native), maxTraceDepth))))

  // ---- Denoiser lifecycle ----

  /** Creates an OptiX HDR denoiser owned by an OptiX context.
    *
    * @param guideAlbedo whether the denoiser requires an albedo guide image
    * @param guideNormal whether the denoiser requires a normal guide image
    * @throws io.github.lene.optix.OptiXException when CUDA or OptiX initialization fails
    */
  def createDenoiser(
    context: ContextHandle,
    guideAlbedo: Boolean,
    guideNormal: Boolean
  ): DenoiserHandle =
    context.adopt(DenoiserHandle(context, created("createDenoiser",
      createDenoiserNative(context.native, guideAlbedo, guideNormal))))

  /** Denoises a row-major linear HDR RGBA float image without guide images.
    *
    * @throws io.github.lene.optix.OptiXException when the native call fails
    */
  def denoiseFloat4(
    denoiser: DenoiserHandle,
    width: Int,
    height: Int,
    colorRgba: Array[Float]
  ): Array[Float] =
    denoised(denoiseFloat4Native(
      denoiser.native,
      width,
      height,
      colorRgba,
      null, // scalafix:ok DisableSyntax.null
      null  // scalafix:ok DisableSyntax.null
    ))

  /** Denoises a row-major linear HDR RGBA float image with optional guide images.
    *
    * All arrays use dense `width * height * 4` float layout.
    *
    * @throws io.github.lene.optix.OptiXException when the native call fails
    */
  def denoiseFloat4(
    denoiser: DenoiserHandle,
    width: Int,
    height: Int,
    colorRgba: Array[Float],
    albedoRgba: Optional[Array[Float]],
    normalRgba: Optional[Array[Float]]
  ): Array[Float] =
    require(
      albedoRgba != null, // scalafix:ok DisableSyntax.null
      "albedoRgba optional must not be null"
    )
    require(
      normalRgba != null, // scalafix:ok DisableSyntax.null
      "normalRgba optional must not be null"
    )
    denoised(denoiseFloat4Native(
      denoiser.native,
      width,
      height,
      colorRgba,
      albedoRgba.orElse(null), // scalafix:ok DisableSyntax.null
      normalRgba.orElse(null)  // scalafix:ok DisableSyntax.null
    ))

  // `raw` by name: the native call runs only after the context check.
  private def group(context: ContextHandle, module: ModuleHandle, what: String, raw: => Long): ProgramGroupHandle =
    requireSameContext(context, module)
    context.adopt(ProgramGroupHandle(context, created(what, raw)))

  private def requireSameContext(context: ContextHandle, child: ContextChild): Unit =
    require(child.context eq context, s"${child.getClass.getSimpleName} belongs to another context")

  // A native create returns 0 when it fails without an exception of its own (it logs why).
  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  private def created(what: String, raw: Long): Long =
    if raw == 0L then throw new OptiXException(s"$what failed (the native log says why)")
    raw

  // The native call returns an empty array for a rejected input or a failed denoise.
  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  private def denoised(result: Array[Float]): Array[Float] =
    if result == null || result.isEmpty then // scalafix:ok DisableSyntax.null
      throw new OptiXException("denoiseFloat4 failed (the native log says why)")
    result

  @native private def createContextNative(): Long
  @native private[api] def destroyContextNative(contextHandle: Long): Unit
  @native private def createModuleFromPTXNative(contextHandle: Long, ptxBytes: Array[Byte]): Long
  @native private[api] def destroyModuleNative(contextHandle: Long, moduleHandle: Long): Unit
  @native private def createRaygenGroupNative(contextHandle: Long, moduleHandle: Long, entryPoint: String): Long
  @native private def createMissGroupNative(contextHandle: Long, moduleHandle: Long, entryPoint: String): Long
  @native private def createHitGroupNative(
    contextHandle: Long, moduleHandle: Long, closestHitEntry: String, isEntry: String): Long
  @native private def createTriangleHitGroupNative(
    contextHandle: Long, moduleHandle: Long, closestHitEntry: String): Long
  @native private def createCurveHitGroupNative(
    contextHandle: Long, moduleHandle: Long, closestHitEntry: String): Long
  @native private[api] def destroyProgramGroupNative(contextHandle: Long, groupHandle: Long): Unit
  @native private def createPipelineNative(
    contextHandle: Long, groupHandles: Array[Long], maxTraceDepth: Int): Long
  @native private[api] def destroyPipelineNative(contextHandle: Long, pipelineHandle: Long): Unit
  @native private def createDenoiserNative(contextHandle: Long, guideAlbedo: Boolean, guideNormal: Boolean): Long
  @native private def denoiseFloat4Native(
    denoiserHandle: Long,
    width: Int,
    height: Int,
    colorRgba: Array[Float],
    albedoRgba: Array[Float],
    normalRgba: Array[Float]
  ): Array[Float]
  @native private[api] def destroyDenoiserNative(denoiserHandle: Long): Unit

/** Singleton access to low-level native OptiX bindings. */
object NativeOptiXApi:
  /** Shared stateless JNI binding instance. */
  val api: NativeOptiXApi = new NativeOptiXApi()
