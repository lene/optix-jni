package io.github.lene.optix

import java.util.Optional

import scala.util.Try

import io.github.lene.optix.api.ContextHandle
import io.github.lene.optix.api.DenoiserHandle
import io.github.lene.optix.api.NativeOptiXApi

/** A linear HDR RGBA float image for denoiser input/output.
  *
  * All floats are row-major dense float4: `width * height * 4` values.
  * NaN and Inf are rejected by [[OptiXDenoiser.denoise]].
  */
final case class DenoiseImage(width: Int, height: Int, rgba: Array[Float])

/** Optional albedo and normal guide images for the HDR denoiser.
  *
  * Guide images use the same dense float4 layout as [[DenoiseImage]].
  * Use [[DenoiseGuides.empty]] when no guides are available; guides measurably
  * improve edge preservation around silhouettes and textured surfaces.
  *
  * Construct via the companion-object factory methods — the constructor is private.
  */
final class DenoiseGuides private (
  val albedo: Optional[Array[Float]],
  val normal: Optional[Array[Float]]
)

/** Standalone OptiX HDR denoiser.
  *
  * Owns its own OptiX context and denoiser handle. Close via [[close]] or a
  * try-with-resources block when done.
  *
  * Denoising runs in linear HDR before tone mapping: pass the accumulated float4
  * render output, denoise, then convert/tone-map to the final image.
  *
  * GPU memory: roughly 100–400 MB depending on resolution and guide usage.
  *
  * @param guideAlbedo require an albedo guide array in every [[denoise]] call
  * @param guideNormal require a normal guide array in every [[denoise]] call
  */
final class OptiXDenoiser(guideAlbedo: Boolean = false, guideNormal: Boolean = false)
    extends AutoCloseable:

  private val api = NativeOptiXApi.api

  // No GPU, no OptiX or a failed denoiser setup leaves the denoiser unavailable, not broken.
  private val handles: Option[(ContextHandle, DenoiserHandle)] =
    if !OptiXRenderer.isLibraryLoaded then None
    else Try(api.createContext()).toOption.flatMap: context =>
      Try(api.createDenoiser(context, guideAlbedo, guideNormal)).fold(
        _ => { context.close(); None },
        denoiser => Some((context, denoiser)))

  /** Returns `true` when the OptiX context and denoiser handle are both live. */
  def isAvailable: Boolean =
    handles.exists((context, denoiser) => context.isOpen && denoiser.isOpen)

  /** Denoises a linear HDR RGBA float image.
    *
    * Input must be finite (no NaN/Inf). When guides were requested at construction
    * time they must be present in `guides`; otherwise they are ignored.
    *
    * @return denoised image, or [[Optional.empty]] if the denoiser is not available
    *         or the native call fails
    * @throws OptiXContextLostException after a sticky CUDA error in this process
    */
  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  def denoise(
    image: DenoiseImage,
    guides: DenoiseGuides = DenoiseGuides.empty()
  ): Optional[DenoiseImage] =
    validateImage(image)
    validateGuides(image, guides)

    handles.filter(_ => isAvailable) match
      case None => Optional.empty()
      case Some((_, denoiser)) =>
        try
          Optional.of(DenoiseImage(image.width, image.height, api.denoiseFloat4(
            denoiser, image.width, image.height, image.rgba, guides.albedo, guides.normal)))
        catch
          case lost: OptiXContextLostException => throw lost // the whole process is affected
          case _: OptiXException => Optional.empty()

  /** Frees the denoiser and its context; closing again does nothing. */
  override def close(): Unit =
    handles.foreach((context, _) => context.close()) // closes the denoiser first

  private def validateImage(image: DenoiseImage): Unit =
    require(image.width > 0, s"width must be positive, got ${image.width}")
    require(image.height > 0, s"height must be positive, got ${image.height}")
    validateFloat4Length("rgba", image.width, image.height, image.rgba)
    validateFinite("rgba", image.rgba)

  private def validateGuides(image: DenoiseImage, guides: DenoiseGuides): Unit =
    require(guides != null, "guides must not be null") // scalafix:ok DisableSyntax.null
    validateOptionalGuide("albedo", image, guides.albedo)
    validateOptionalGuide("normal", image, guides.normal)
    require(
      !guideAlbedo || guides.albedo.isPresent,
      "albedo guide is required by this denoiser"
    )
    require(
      !guideNormal || guides.normal.isPresent,
      "normal guide is required by this denoiser"
    )

  private def validateOptionalGuide(
    name: String,
    image: DenoiseImage,
    guide: Optional[Array[Float]]
  ): Unit =
    require(
      guide != null, // scalafix:ok DisableSyntax.null
      s"$name guide optional must not be null"
    )
    guide.ifPresent { values =>
      validateFloat4Length(name, image.width, image.height, values)
      validateFinite(name, values)
    }

  private def validateFloat4Length(
    name: String,
    width: Int,
    height: Int,
    values: Array[Float]
  ): Unit =
    require(values != null, s"$name must not be null") // scalafix:ok DisableSyntax.null
    val expectedLength = expectedFloat4Length(width, height)
    require(
      values.length == expectedLength,
      s"$name length must be $expectedLength for ${width}x$height RGBA float4, got ${values.length}"
    )

  private def expectedFloat4Length(width: Int, height: Int): Int =
    val pixels = width.toLong * height.toLong
    val floats = pixels * OptiXDenoiser.floatsPerPixel
    require(floats <= Int.MaxValue, s"image is too large: ${width}x$height")
    floats.toInt

  private def validateFinite(name: String, values: Array[Float]): Unit =
    require(values.forall(java.lang.Float.isFinite), s"$name must contain only finite floats")

object OptiXDenoiser:
  private val floatsPerPixel: Int = 4

object DenoiseGuides:
  private val emptyGuides = new DenoiseGuides(Optional.empty(), Optional.empty())

  /** No guide images — uses color-only denoising. */
  def empty(): DenoiseGuides = emptyGuides

  /** Albedo guide only. */
  def albedo(albedoRgba: Array[Float]): DenoiseGuides =
    new DenoiseGuides(present(albedoRgba, "albedo"), Optional.empty())

  /** Normal guide only. */
  def normal(normalRgba: Array[Float]): DenoiseGuides =
    new DenoiseGuides(Optional.empty(), present(normalRgba, "normal"))

  /** Both albedo and normal guides (best edge preservation). */
  def albedoAndNormal(albedoRgba: Array[Float], normalRgba: Array[Float]): DenoiseGuides =
    new DenoiseGuides(
      present(albedoRgba, "albedo"),
      present(normalRgba, "normal")
    )

  def optional(
    albedoRgba: Optional[Array[Float]],
    normalRgba: Optional[Array[Float]]
  ): DenoiseGuides =
    require(
      albedoRgba != null, // scalafix:ok DisableSyntax.null
      "albedoRgba optional must not be null"
    )
    require(
      normalRgba != null, // scalafix:ok DisableSyntax.null
      "normalRgba optional must not be null"
    )
    new DenoiseGuides(albedoRgba, normalRgba)

  private def present(values: Array[Float], name: String): Optional[Array[Float]] =
    require(values != null, s"$name guide must not be null") // scalafix:ok DisableSyntax.null
    Optional.of(values)
