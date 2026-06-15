package io.github.lene.optix

import io.github.lene.optix.api.NativeOptiXApi

final case class DenoiseImage(width: Int, height: Int, rgba: Array[Float])

final case class DenoiseGuides(
  albedo: Option[Array[Float]] = None,
  normal: Option[Array[Float]] = None
)

final class OptiXDenoiser(guideAlbedo: Boolean = false, guideNormal: Boolean = false)
    extends AutoCloseable:

  private val api = NativeOptiXApi.api
  private val nativeLibraryLoaded = OptiXRenderer.isLibraryLoaded

  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  private var contextHandle: Long =
    if nativeLibraryLoaded then api.createContext() else 0L

  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  private var denoiserHandle: Long =
    if contextHandle == 0L then 0L
    else api.createDenoiser(contextHandle, guideAlbedo, guideNormal)

  def isAvailable: Boolean =
    contextHandle != 0L && denoiserHandle != 0L

  def denoise(image: DenoiseImage, guides: DenoiseGuides = DenoiseGuides()): Option[DenoiseImage] =
    validateImage(image)
    validateGuides(image, guides)

    if !isAvailable then None
    else
      val result = api.denoiseFloat4(
        denoiserHandle,
        image.width,
        image.height,
        image.rgba,
        guides.albedo,
        guides.normal
      )
      if result == null || result.isEmpty then None // scalafix:ok DisableSyntax.null
      else Some(DenoiseImage(image.width, image.height, result))

  override def close(): Unit =
    if denoiserHandle != 0L then
      api.destroyDenoiser(denoiserHandle)
      denoiserHandle = 0L
    if contextHandle != 0L then
      api.destroyContext(contextHandle)
      contextHandle = 0L

  private def validateImage(image: DenoiseImage): Unit =
    require(image.width > 0, s"width must be positive, got ${image.width}")
    require(image.height > 0, s"height must be positive, got ${image.height}")
    validateFloat4Length("rgba", image.width, image.height, image.rgba)
    validateFinite("rgba", image.rgba)

  private def validateGuides(image: DenoiseImage, guides: DenoiseGuides): Unit =
    validateOptionalGuide("albedo", image, guides.albedo)
    validateOptionalGuide("normal", image, guides.normal)
    require(
      !guideAlbedo || guides.albedo.nonEmpty,
      "albedo guide is required by this denoiser"
    )
    require(
      !guideNormal || guides.normal.nonEmpty,
      "normal guide is required by this denoiser"
    )

  private def validateOptionalGuide(
    name: String,
    image: DenoiseImage,
    guide: Option[Array[Float]]
  ): Unit =
    guide.foreach { values =>
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
