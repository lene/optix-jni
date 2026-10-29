package io.github.lene.optix

import java.util.Optional

import menger.common.CausticsConfig
import menger.common.ImageSize
import menger.common.RenderConfig

/** Render-configuration and frame rendering helpers exposed through [[OptiXRenderer]].
  *
  * Methods in this trait map common Scala configuration objects to native OptiX
  * state. `renderWithStats` returns `java.util.Optional` so the standalone
  * JNI API remains convenient for Java and Kotlin consumers.
  */
private[optix] trait OptiXRenderApi:
  this: OptiXRenderer =>

  /** Applies shadow, antialiasing, ray-depth, and tone-mapping settings. */
  def setRenderConfig(config: RenderConfig): Unit =
    setShadows(config.shadows)
    setTransparentShadows(config.transparentShadows)
    setAntialiasing(config.antialiasing, config.aaMaxDepth, config.aaThreshold)
    setMaxRayDepth(config.maxRayDepth)
    setToneMapping(config.toneMappingOperator, config.toneMappingExposure)

  /** Sets native tone mapping by operator id and exposure multiplier. */
  def setToneMapping(operator: Int, exposure: Float): Unit =
    setToneMappingNative(operator, exposure)

  /** Configures image-based lighting strength and sample count. */
  def setIBL(enabled: Boolean, strength: Float, samples: Int): Unit =
    setIBLNative(enabled, strength, samples)

  /** Sets temporal accumulation frame count for noisy render paths.
    *
    * @throws IllegalStateException when the renderer is not initialized (optix-jni#72: this
    *         used to be skipped silently)
    */
  def setAccumulationFrames(n: Int): Unit =
    setAccumulationFramesNative(n)

  /** Applies progressive photon-mapping caustics settings. */
  def setCausticsConfig(config: CausticsConfig): Unit =
    setCaustics(config.enabled, config.photonsPerIteration, config.iterations, config.initialRadius, config.alpha)

  /** Enables caustics using explicit photon-map parameters. */
  def enableCaustics(
    photonsPerIter: Int = 100000,
    iterations: Int = 10,
    initialRadius: Float = 1.0f,
    alpha: Float = 0.7f
  ): Unit =
    setCaustics(true, photonsPerIter, iterations, initialRadius, alpha)

  /** Disables caustics and clears native photon-map parameters. */
  def disableCaustics(): Unit =
    setCaustics(false, 0, 0, 0.0f, 0.0f)

  /** Returns caustics statistics, or `null` when caustics are disabled or empty. */
  def getCausticsStats: CausticsStats =
    try
      val stats = getCausticsStatsNative()
      if stats != null && stats.photonsEmitted > 0 then stats else null // scalafix:ok DisableSyntax.null
    catch
      // S2 #7: log rather than silently swallow — soft-continue (return null) is still the
      // right behavior here (stats are advisory), but a silent catch-all hid every failure.
      case e: Exception =>
        logger.error("getCausticsStats failed", e)
        null // scalafix:ok DisableSyntax.null

  /** Renders an RGBA8 frame and ray statistics for an [[menger.common.ImageSize]].
    *
    * @return `java.util.Optional` containing [[RenderResult]] with elapsed frame time
    * @throws IllegalStateException when the renderer is not initialized
    * @throws OptiXContextLostException after a sticky CUDA error earlier in this process
    * @throws OptiXException on any other native failure
    */
  def renderWithStats(size: ImageSize): Optional[RenderResult] =
    val startNs = System.nanoTime()
    val raw = renderWithStats(size.width, size.height)
    val elapsedMs = (System.nanoTime() - startNs).toFloat / 1_000_000f
    Optional.ofNullable(raw).map((r: RenderResult) => r.copy(frameMs = elapsedMs))

  /** One image mixed from several renders that differ only in which instances are visible
    * (optix-jni#85): in each pass, `instanceIds(i)` is visible iff `pass.visible(i)`; the passes'
    * RGBA8 images are summed per channel with their weights, rounded and clamped. Ray stats are
    * summed. One native call and one image, instead of one render, one array and one JVM mix
    * per pass. Visibility stays as the last pass set it. At most 64 passes.
    *
    * @return the mixed frame, or empty when the renderer is not initialized
    */
  def renderMix(size: ImageSize, instanceIds: Array[InstanceId], passes: Array[MixPass]): Optional[RenderResult] =
    require(passes.nonEmpty, "renderMix needs at least one pass")
    require(passes.forall(_.visible.length == instanceIds.length),
      "every pass names the visibility of every instance")
    val startNs = System.nanoTime()
    val raw = renderMixNative(size.width, size.height, instanceIds.values,
      passes.flatMap(_.visible), passes.map(_.weight))
    val elapsedMs = (System.nanoTime() - startNs).toFloat / 1_000_000f
    Optional.ofNullable(raw).map((r: RenderResult) => r.copy(frameMs = elapsedMs))

  /** Renders an RGBA8 image.
    *
    * @return row-major RGBA bytes, or `null` on failure
    */
  def render(size: ImageSize): Array[Byte] =
    renderWithStats(size).map((r: RenderResult) => r.image).orElse(null) // scalafix:ok DisableSyntax.null

  /** Renders an RGBA8 image at the supplied pixel dimensions.
    *
    * @return row-major RGBA bytes, or `null` on failure
    */
  def render(width: Int, height: Int): Array[Byte] =
    render(ImageSize(width, height))

/** One pass of [[OptiXRenderApi.renderMix]]: which of the mix's instances it shows, and the
  * weight of its image in the mix. */
final case class MixPass(visible: Array[Boolean], weight: Double)
