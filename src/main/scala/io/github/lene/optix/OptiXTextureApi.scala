package io.github.lene.optix

/** Texture and environment-map helpers exposed through [[OptiXRenderer]].
  *
  * A [[TextureIndex]] is a native slot returned by an upload method;
  * [[TextureIndex.NoTexture]] leaves an optional per-instance map off.
  */
private[optix] trait OptiXTextureApi:
  this: OptiXRenderer =>

  /** Uses an uploaded texture as the environment map. */
  def setEnvironmentMap(textureIndex: TextureIndex): Unit =
    require(textureIndex.value >= 0, "the environment map needs a texture, not NoTexture")
    setEnvironmentMapNative(textureIndex.value)

  /** Assigns a procedural texture to an IAS instance.
    *
    * `proceduralType` must be one of [[ProceduralType]] ids. Scale is in
    * world-space texture units and must be positive.
    */
  def setProceduralTexture(instanceId: InstanceId, proceduralType: Int, proceduralScale: Float = 1.0f): Unit =
    require(proceduralType >= 0 && proceduralType <= 11, "proceduralType must be 0–11")
    require(proceduralScale > 0f, "proceduralScale must be positive")
    setProceduralTextureNative(instanceId.value, proceduralType, proceduralScale)

  /** Gives an IAS instance its object frame: a row-major 3x4 world -> object-local transform
    * that maps the object's bounding box onto [0,1]^3 and follows its position, size and
    * rotation. [[ProceduralType.XYZToRGBLocal]] colours by this local position; a transparent
    * refractive instance with a frame casts the shadow of the colour seen through it
    * (Beer-Lambert over its mean chord, 2/3 of the box extent). Without a frame, the local
    * colouring falls back to world coordinates and shadows keep the surface model.
    */
  def setObjectFrame(instanceId: InstanceId, worldToLocal: Array[Float]): Unit =
    require(worldToLocal.length == 12, "worldToLocal must be a 3x4 matrix (12 floats, row-major)")
    require(worldToLocal.forall(v => !v.isNaN && !v.isInfinite), "worldToLocal must be finite")
    setObjectFrameNative(instanceId.value, worldToLocal)

  /** Assigns optional normal and roughness texture maps to an IAS instance. */
  def setMapTextures(instanceId: InstanceId,
      normalTextureIndex: TextureIndex = TextureIndex.NoTexture,
      roughnessTextureIndex: TextureIndex = TextureIndex.NoTexture,
      metallicTextureIndex: TextureIndex = TextureIndex.NoTexture,
      aoTextureIndex: TextureIndex = TextureIndex.NoTexture,
      heightTextureIndex: TextureIndex = TextureIndex.NoTexture): Unit =
    setMapTexturesNative(instanceId.value, normalTextureIndex.value, roughnessTextureIndex.value,
      metallicTextureIndex.value, aoTextureIndex.value, heightTextureIndex.value)

  /** Sets the image texture slot for cone and plane instances.
    *
    * These geometry types currently use separate native fields for geometry data
    * indexing and image texture indexing. Task 26.5 tracks completing that
    * separation in native naming.
    */
  def setImageTexture(instanceId: InstanceId, imageTextureIndex: TextureIndex): Unit =
    setImageTextureNative(instanceId.value, imageTextureIndex.value)

  /** Dimensions positive and bounded, size computed in `Long` (optix-jni#68): `width * height * 4`
    * in `Int` wrapped, so a far too short array passed. The native entry checks the same. */
  private def requireRgbaImage(imageData: Array[Byte], width: Int, height: Int): Unit =
    require(width > 0, s"Width must be positive, got $width")
    require(height > 0, s"Height must be positive, got $height")
    val pixels = width.toLong * height.toLong
    require(pixels <= OptiXTextureApi.MaxPixels,
      s"Texture ${width}x$height exceeds ${OptiXTextureApi.MaxPixels} pixels")
    require(
      imageData.length.toLong == pixels * 4,
      s"Image data size mismatch: expected ${pixels * 4} bytes (${width}x${height}x4), " +
        s"got ${imageData.length}"
    )

  /** Uploads an RGBA8 texture buffer into a native texture slot.
    *
    * @param imageData row-major RGBA bytes, length `width * height * 4`
    * @return texture index `>= 0`
    * @throws TextureUploadException when native upload returns a negative code
    */
  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  def uploadTexture(name: String, imageData: Array[Byte], width: Int, height: Int): TextureIndex =
    // JNI boundary validation - null checks required for native method safety
    require(name != null && name.nonEmpty, "Texture name must not be null or empty") // scalafix:ok DisableSyntax.null
    require(imageData != null, "Image data must not be null") // scalafix:ok DisableSyntax.null
    requireRgbaImage(imageData, width, height)
    val index = uploadTextureNative(name, imageData, width, height)
    if index < 0 then
      throw TextureUploadException(s"Failed to upload texture '$name': error code $index")
    else
      TextureIndex(index)

  /** Updates an existing RGBA8 native texture slot in place.
    *
    * The update must use the same dimensions as the originally uploaded texture.
    *
    * @param imageData row-major RGBA bytes, length `width * height * 4`
    * @throws TextureUploadException when native update returns a negative code
    */
  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  def updateTexture(textureIndex: TextureIndex, imageData: Array[Byte], width: Int, height: Int): Unit =
    require(textureIndex.value >= 0, "updateTexture needs a texture, not NoTexture")
    require(imageData != null, "Image data must not be null") // scalafix:ok DisableSyntax.null
    requireRgbaImage(imageData, width, height)
    val result = updateTextureNative(textureIndex.value, imageData, width, height)
    if result < 0 then
      throw TextureUploadException(s"Failed to update texture ${textureIndex.value}: error code $result")

  /** Uploads a texture from a native-readable file path.
    *
    * @return texture index `>= 0`
    * @throws TextureUploadException when native upload returns a negative code
    */
  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  def uploadTextureFromFile(path: String): TextureIndex =
    require(path != null && path.nonEmpty, "Path must not be null or empty") // scalafix:ok DisableSyntax.null
    val index = uploadTextureFromFileNative(path)
    if index < 0 then
      throw TextureUploadException(s"Failed to upload texture from file '$path': error code $index")
    else
      TextureIndex(index)

  /** Releases all native texture objects owned by this renderer. */
  def releaseTextures(): Unit =
    releaseTexturesNative()

private[optix] object OptiXTextureApi:
  /** The largest texture or image: 16384 x 16384 pixels (native `jni_checks::kMaxPixels`). */
  val MaxPixels: Long = 16384L * 16384L
