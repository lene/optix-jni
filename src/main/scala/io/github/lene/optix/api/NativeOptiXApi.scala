package io.github.lene.optix.api

/** Thin JNI bindings for the core OptiX 7.x API.
 *
 *  Each method wraps one C++ OptiXContext operation. Handles are raw Long values
 *  (opaque pointers); callers are responsible for lifecycle.
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

  /** Creates and initialises a new OptiX device context. Returns 0L on failure. */
  @native def createContext(): Long

  /** Destroys a context created by [[createContext]]. Safe to call with 0L. */
  @native def destroyContext(contextHandle: Long): Unit

  // ---- Module lifecycle ----

  /** Creates an OptiX module from PTX bytecode. Returns 0L on failure. */
  @native def createModuleFromPTX(contextHandle: Long, ptxBytes: Array[Byte]): Long

  /** Destroys a module created by [[createModuleFromPTX]]. */
  @native def destroyModule(contextHandle: Long, moduleHandle: Long): Unit

  // ---- Program group lifecycle ----

  /** Creates a raygen program group. Returns 0L on failure. */
  @native def createRaygenGroup(contextHandle: Long, moduleHandle: Long, entryPoint: String): Long

  /** Creates a miss program group. Returns 0L on failure. */
  @native def createMissGroup(contextHandle: Long, moduleHandle: Long, entryPoint: String): Long

  /** Creates a hitgroup for custom primitives (closesthit + intersection shaders). Returns 0L on failure. */
  @native def createHitGroup(contextHandle: Long, moduleHandle: Long, closestHitEntry: String, isEntry: String): Long

  /** Creates a hitgroup for triangle geometry (built-in intersection, no IS shader). Returns 0L on failure. */
  @native def createTriangleHitGroup(contextHandle: Long, moduleHandle: Long, closestHitEntry: String): Long

  /** Destroys a program group. */
  @native def destroyProgramGroup(contextHandle: Long, groupHandle: Long): Unit

  // ---- Pipeline lifecycle ----

  /** Creates a pipeline from a set of program groups. Returns 0L on failure.
   *  @param maxTraceDepth maximum ray recursion depth
   */
  @native def createPipeline(
    contextHandle: Long,
    groupHandles: Array[Long],
    maxTraceDepth: Int
  ): Long

  /** Destroys a pipeline. */
  @native def destroyPipeline(contextHandle: Long, pipelineHandle: Long): Unit

object NativeOptiXApi:
  val api: NativeOptiXApi = new NativeOptiXApi()
