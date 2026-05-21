package menger.optix

import scala.util.Failure
import scala.util.Success
import scala.util.Try

private[optix] trait OptiXTextureApi:
  this: OptiXRenderer =>

  def setEnvironmentMap(textureIndex: Int): Unit =
    require(textureIndex >= 0, "textureIndex must be >= 0")
    setEnvironmentMapNative(textureIndex)

  def setProceduralTexture(instanceId: Int, proceduralType: Int, proceduralScale: Float = 1.0f): Unit =
    require(proceduralType >= 0 && proceduralType <= 10, "proceduralType must be 0–10")
    require(proceduralScale > 0f, "proceduralScale must be positive")
    setProceduralTextureNative(instanceId, proceduralType, proceduralScale)

  def setMapTextures(instanceId: Int, normalTextureIndex: Int = -1, roughnessTextureIndex: Int = -1): Unit =
    setMapTexturesNative(instanceId, normalTextureIndex, roughnessTextureIndex)

  /** Set image texture for cone/plane instances (Task 21.6).
   *  For these geometry types, texture_index is the geometry data index,
   *  so image texture uses image_texture_index instead. */
  def setImageTexture(instanceId: Int, imageTextureIndex: Int): Unit =
    setImageTextureNative(instanceId, imageTextureIndex)

  def uploadTexture(name: String, imageData: Array[Byte], width: Int, height: Int): Try[Int] =
    // JNI boundary validation - null checks required for native method safety
    require(name != null && name.nonEmpty, "Texture name must not be null or empty") // scalafix:ok DisableSyntax.null
    require(imageData != null, "Image data must not be null") // scalafix:ok DisableSyntax.null
    require(width > 0, s"Width must be positive, got $width")
    require(height > 0, s"Height must be positive, got $height")
    val expectedSize = width * height * 4  // RGBA, 4 bytes per pixel
    require(
      imageData.length == expectedSize,
      s"Image data size mismatch: expected $expectedSize bytes (${width}x${height}x4), got ${imageData.length}"
    )
    val index = uploadTextureNative(name, imageData, width, height)
    if index < 0 then
      Failure(TextureUploadException(s"Failed to upload texture '$name': error code $index"))
    else
      Success(index)

  def uploadTextureFromFile(path: String): Int =
    require(path != null && path.nonEmpty, "Path must not be null or empty") // scalafix:ok DisableSyntax.null
    uploadTextureFromFileNative(path)

  def releaseTextures(): Unit =
    releaseTexturesNative()
