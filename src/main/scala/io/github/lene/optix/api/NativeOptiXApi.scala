package io.github.lene.optix.api

/** Thin JNI bindings for selected OptiX 7.x context operations.
 *
 *  Each method wraps one C++ `OptiXContext` operation and exposes native handles
 *  as raw `Long` values. A non-zero handle represents ownership of a native
 *  resource and must be destroyed with the matching destroy method. A `0L`
 *  handle means creation failed or the resource is absent; destroy methods are
 *  safe to call with `0L` so callers can clean up conditionally-created graphs.
 *
 *  Ownership is hierarchical: modules, program groups, and pipelines belong to
 *  the context handle they were created from. Destroy child handles before the
 *  context. Entry point names must match symbols compiled into the supplied PTX.
 *
 *  Usage:
 *  {{{
 *    val api = NativeOptiXApi.api
 *    val ctx = api.createContext()
 *    // ctx == 0L means GPU/OptiX not available
 *    if (ctx != 0L) {
 *      val module = api.createModuleFromPTX(ctx, ptxBytes)
 *      val rg     = api.createRaygenGroup(ctx, module, "__raygen__main")
 *      val miss   = api.createMissGroup(ctx, module, "__miss__main")
 *      val hit    = api.createHitGroup(ctx, module, "__closesthit__main")
 *      val pipeline = api.createPipeline(ctx, Array(rg, miss, hit), maxTraceDepth = 2)
 *      // ... render ...
 *      api.destroyPipeline(ctx, pipeline)
 *      api.destroyProgramGroup(ctx, hit)
 *      api.destroyProgramGroup(ctx, miss)
 *      api.destroyProgramGroup(ctx, rg)
 *      api.destroyModule(ctx, module)
 *      api.destroyContext(ctx)
 *    }
 *  }}}
 */
class NativeOptiXApi:

  // ---- Context lifecycle ----

  /** Creates and initializes a new OptiX device context.
    *
    * @return non-zero context handle on success, or `0L` when CUDA or OptiX
    *         initialization fails
    */
  @native def createContext(): Long

  /** Destroys a context created by [[createContext]].
    *
    * Safe to call with `0L`. Other handles created from the context should be
    * destroyed before this call.
    */
  @native def destroyContext(contextHandle: Long): Unit

  // ---- Module lifecycle ----

  /** Creates an OptiX module from PTX bytecode.
    *
    * @param contextHandle owning context returned by [[createContext]]
    * @param ptxBytes UTF-8 PTX source bytes
    * @return non-zero module handle, or `0L` when compilation fails
    */
  @native def createModuleFromPTX(contextHandle: Long, ptxBytes: Array[Byte]): Long

  /** Destroys a module created by [[createModuleFromPTX]].
    *
    * Safe to call with `0L`. Destroy program groups that reference the module
    * before destroying the module.
    */
  @native def destroyModule(contextHandle: Long, moduleHandle: Long): Unit

  // ---- Program group lifecycle ----

  /** Creates a ray-generation program group from a module entry point.
    *
    * @return non-zero program-group handle, or `0L` on failure
    */
  @native def createRaygenGroup(contextHandle: Long, moduleHandle: Long, entryPoint: String): Long

  /** Creates a miss program group from a module entry point.
    *
    * @return non-zero program-group handle, or `0L` on failure
    */
  @native def createMissGroup(contextHandle: Long, moduleHandle: Long, entryPoint: String): Long

  /** Creates a custom-primitive hitgroup.
    *
    * `closestHitEntry` and `isEntry` name closest-hit and intersection symbols
    * in the module.
    *
    * @return non-zero program-group handle, or `0L` on failure
    */
  @native def createHitGroup(
    contextHandle: Long,
    moduleHandle: Long,
    closestHitEntry: String,
    isEntry: String
  ): Long

  /** Creates a triangle-geometry hitgroup using OptiX built-in intersection.
    *
    * @return non-zero program-group handle, or `0L` on failure
    */
  @native def createTriangleHitGroup(
    contextHandle: Long,
    moduleHandle: Long,
    closestHitEntry: String
  ): Long

  /** Destroys a program group. Safe to call with `0L`. */
  @native def destroyProgramGroup(contextHandle: Long, groupHandle: Long): Unit

  // ---- Pipeline lifecycle ----

  /** Creates a pipeline from program groups.
    *
    * @param groupHandles raygen, miss, and hitgroup handles from this context
    * @param maxTraceDepth maximum ray recursion depth
    * @return non-zero pipeline handle, or `0L` on failure
    */
  @native def createPipeline(
    contextHandle: Long,
    groupHandles: Array[Long],
    maxTraceDepth: Int
  ): Long

  /** Destroys a pipeline. Safe to call with `0L`. */
  @native def destroyPipeline(contextHandle: Long, pipelineHandle: Long): Unit

  // ---- Denoiser lifecycle ----

  /** Creates an OptiX HDR denoiser owned by an OptiX context.
    *
    * @param contextHandle owning context returned by [[createContext]]
    * @param guideAlbedo whether the denoiser requires an albedo guide image
    * @param guideNormal whether the denoiser requires a normal guide image
    * @return non-zero denoiser handle, or `0L` when CUDA or OptiX initialization fails
    */
  @native def createDenoiser(
    contextHandle: Long,
    guideAlbedo: Boolean,
    guideNormal: Boolean
  ): Long

  @native private def denoiseFloat4Native(
    denoiserHandle: Long,
    width: Int,
    height: Int,
    colorRgba: Array[Float],
    albedoRgba: Array[Float],
    normalRgba: Array[Float]
  ): Array[Float]

  /** Denoises a row-major linear HDR RGBA float image without guide images. */
  def denoiseFloat4(
    denoiserHandle: Long,
    width: Int,
    height: Int,
    colorRgba: Array[Float]
  ): Array[Float] =
    denoiseFloat4Native(
      denoiserHandle,
      width,
      height,
      colorRgba,
      null, // scalafix:ok DisableSyntax.null
      null  // scalafix:ok DisableSyntax.null
    )

  /** Denoises a row-major linear HDR RGBA float image with optional guide images.
    *
    * All arrays use dense `width * height * 4` float layout.
    */
  def denoiseFloat4(
    denoiserHandle: Long,
    width: Int,
    height: Int,
    colorRgba: Array[Float],
    albedoRgba: Option[Array[Float]],
    normalRgba: Option[Array[Float]]
  ): Array[Float] =
    denoiseFloat4Native(
      denoiserHandle,
      width,
      height,
      colorRgba,
      albedoRgba.orNull,
      normalRgba.orNull
    )

  /** Destroys a denoiser created by [[createDenoiser]]. Safe to call with `0L`. */
  @native def destroyDenoiser(denoiserHandle: Long): Unit

/** Singleton access to low-level native OptiX bindings. */
object NativeOptiXApi:
  /** Shared stateless JNI binding instance. */
  val api: NativeOptiXApi = new NativeOptiXApi()
