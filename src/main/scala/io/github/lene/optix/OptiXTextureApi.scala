package io.github.lene.optix

/** Texture and environment-map helpers exposed through [[OptiXRenderer]].
  *
  * Texture indices are native slots returned by upload methods. `-1` disables
  * optional per-instance maps unless a method requires a non-negative index.
  */
private[optix] trait OptiXTextureApi:
  this: OptiXRenderer =>

  /** Uses an uploaded texture as the environment map. */
  def setEnvironmentMap(textureIndex: Int): Unit =
    require(textureIndex >= 0, "textureIndex must be >= 0")
    setEnvironmentMapNative(textureIndex)

  /** Assigns a procedural texture to an IAS instance.
    *
    * `proceduralType` must be one of [[ProceduralType]] ids. Scale is in
    * world-space texture units and must be positive.
    */
  def setProceduralTexture(instanceId: Int, proceduralType: Int, proceduralScale: Float = 1.0f): Unit =
    require(proceduralType >= 0 && proceduralType <= 10, "proceduralType must be 0–10")
    require(proceduralScale > 0f, "proceduralScale must be positive")
    setProceduralTextureNative(instanceId, proceduralType, proceduralScale)

  /** Assigns optional normal and roughness texture maps to an IAS instance. */
  def setMapTextures(instanceId: Int, normalTextureIndex: Int = -1, roughnessTextureIndex: Int = -1): Unit =
    setMapTexturesNative(instanceId, normalTextureIndex, roughnessTextureIndex)

  /** Sets the image texture slot for cone and plane instances.
    *
    * These geometry types currently use separate native fields for geometry data
    * indexing and image texture indexing. Task 26.5 tracks completing that
    * separation in native naming.
    */
  def setImageTexture(instanceId: Int, imageTextureIndex: Int): Unit =
    setImageTextureNative(instanceId, imageTextureIndex)

  /** Uploads an RGBA8 texture buffer into a native texture slot.
    *
    * @param imageData row-major RGBA bytes, length `width * height * 4`
    * @return texture index `>= 0`
    * @throws TextureUploadException when native upload returns a negative code
    */
  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  def uploadTexture(name: String, imageData: Array[Byte], width: Int, height: Int): Int =
    // JNI boundary validation - null checks required for native method safety
    require(name != null && name.nonEmpty, "Texture name must not be null or empty") // scalafix:ok DisableSyntax.null
    require(imageData != null, "Image data must not be null") // scalafix:ok DisableSyntax.null
    require(width > 0, s"Width must be positive, got $width")
    require(height > 0, s"Height must be positive, got $height")
    val expectedSize = width * height * 4  // RGBA, 4 bytes per pixel
    require(
      imageData.length == expectedSize,
      s"Image data size mismatch: expected $expectedSize bytes " +
        s"(${width}x${height}x4), got ${imageData.length}"
    )
    val index = uploadTextureNative(name, imageData, width, height)
    if index < 0 then
      throw TextureUploadException(s"Failed to upload texture '$name': error code $index")
    else
      index

  /** Updates an existing RGBA8 native texture slot in place.
    *
    * The update must use the same dimensions as the originally uploaded texture.
    *
    * @param imageData row-major RGBA bytes, length `width * height * 4`
    * @throws TextureUploadException when native update returns a negative code
    */
  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  def updateTexture(textureIndex: Int, imageData: Array[Byte], width: Int, height: Int): Unit =
    require(textureIndex >= 0, "textureIndex must be >= 0")
    require(imageData != null, "Image data must not be null") // scalafix:ok DisableSyntax.null
    require(width > 0, s"Width must be positive, got $width")
    require(height > 0, s"Height must be positive, got $height")
    val expectedSize = width * height * 4  // RGBA, 4 bytes per pixel
    require(
      imageData.length == expectedSize,
      s"Image data size mismatch: expected $expectedSize bytes " +
        s"(${width}x${height}x4), got ${imageData.length}"
    )
    val result = updateTextureNative(textureIndex, imageData, width, height)
    if result < 0 then
      throw TextureUploadException(s"Failed to update texture $textureIndex: error code $result")

  /** Uploads a texture from a native-readable file path.
    *
    * @return texture index `>= 0`
    * @throws TextureUploadException when native upload returns a negative code
    */
  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  def uploadTextureFromFile(path: String): Int =
    require(path != null && path.nonEmpty, "Path must not be null or empty") // scalafix:ok DisableSyntax.null
    val index = uploadTextureFromFileNative(path)
    if index < 0 then
      throw TextureUploadException(s"Failed to upload texture from file '$path': error code $index")
    else
      index

  /** Releases all native texture objects owned by this renderer. */
  def releaseTextures(): Unit =
    releaseTexturesNative()
