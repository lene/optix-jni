package io.github.lene.optix

import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files

import scala.util.Failure
import scala.util.Success
import scala.util.Try
import scala.util.control.Exception.catching

import com.typesafe.scalalogging.LazyLogging
import menger.common.Color
import menger.common.ImageSize
import menger.common.Vector
import menger.common.toArray
import menger.common.{Light => CommonLight}

// Light source types (must match C++ LightType enum)
private object LightTypeJNI:
  val DIRECTIONAL: Int = 0  // Parallel rays from infinity (sun-like), no distance attenuation
  val POINT: Int = 1         // Radiate from position, inverse-square falloff
  val AREA: Int = 2          // Finite-size disk emitter, soft shadows via multi-sample

/** Area-light shape ids passed through JNI to the native renderer. */
object AreaLightShape:
  /** Circular disk emitter. This is the only supported area-light shape. */
  val DISK: Int = 0

/** JNI light payload consumed by native `OptiXRenderer.setLights`.
  *
  * The class must remain accessible to JNI as `io/github/lene/optix/Light`.
  * Direction arrays are used by directional lights; position arrays are used by
  * point and area lights. RGB color channels and intensity are linear values.
  */
case class Light(
  lightType: Int,
  direction: Array[Float],
  position: Array[Float],
  color: Array[Float],
  intensity: Float,
  shape: Int = AreaLightShape.DISK,
  normal: Array[Float] = Array(0f, -1f, 0f),
  radius: Float = 1.0f,
  shadowSamples: Int = 4
)

// Private helper to convert common.Light to JNI representation
private def toJNILight(light: CommonLight): Light =
  light match
    case CommonLight.Directional(dir, color, intensity) =>
      Light(
        lightType = LightTypeJNI.DIRECTIONAL,
        direction = dir.toArray,
        position = Array(0f, 0f, 0f),  // Unused for directional
        color = color.toRGBArray,
        intensity = intensity
      )
    case CommonLight.Point(pos, color, intensity) =>
      Light(
        lightType = LightTypeJNI.POINT,
        direction = Array(0f, 0f, 0f),  // Unused for point
        position = pos.toArray,
        color = color.toRGBArray,
        intensity = intensity
      )
    case CommonLight.Area(pos, norm, radius, shape, color, intensity, samples) =>
      Light(
        lightType = LightTypeJNI.AREA,
        direction = Array(0f, 0f, 0f),  // Unused for area
        position = pos.toArray,
        color = color.toRGBArray,
        intensity = intensity,
        shape = shape.id,
        normal = norm.toArray,
        radius = radius,
        shadowSamples = samples
      )

/** Per-frame ray counters returned by the native renderer.
  *
  * Counters are monotonically accumulated for a single render call. Depth fields
  * report the deepest and shallowest recursive ray depths reached in that frame.
  * `frameMs` is elapsed render time in milliseconds.
  */
case class RayStats(
  totalRays: Long,
  primaryRays: Long,
  reflectedRays: Long,
  refractedRays: Long,
  shadowRays: Long,
  aaRays: Long,
  aaStackOverflows: Long,
  maxDepthReached: Int,
  minDepthReached: Int,
  frameMs: Float
):
  /** Milliseconds per million rays (0 if no rays traced). */
  def msPerMray: Float =
    if totalRays == 0L then 0.0f
    else frameMs / (totalRays.toFloat / 1_000_000f)

/** Progressive photon mapping caustics counters from native OptiX code.
  *
  * The field order matches `CausticsStats` in `OptiXData.h`. Flux fields are
  * linear energy estimates; timing fields are milliseconds for the corresponding
  * caustics stage.
  */
case class CausticsStats(
  // C1: Photon emission
  photonsEmitted: Long,
  photonsTowardSphere: Long,
  // C2: Sphere hit rate
  sphereHits: Long,
  sphereMisses: Long,
  // C3: Refraction events
  refractionEvents: Long,
  tirEvents: Long,
  // C4: Deposition
  photonsDeposited: Long,
  hitPointsWithFlux: Long,
  // C5: Energy conservation
  totalFluxEmitted: Double,
  totalFluxDeposited: Double,
  totalFluxAbsorbed: Double,
  totalFluxReflected: Double,
  // C6: Convergence metrics
  avgRadius: Float,
  minRadius: Float,
  maxRadius: Float,
  fluxVariance: Float,
  // C7: Brightness metrics
  maxCausticBrightness: Float,
  avgFloorBrightness: Float,
  // Timing
  hitPointGenerationMs: Float,
  photonTracingMs: Float,
  radianceComputationMs: Float
):
  // Derived metrics for validation
  def sphereHitRate: Double =
    if photonsTowardSphere > 0 then sphereHits.toDouble / photonsTowardSphere else 0.0

  def energyConservationError: Double =
    if totalFluxEmitted > 0 then
      val totalOutput = totalFluxDeposited + totalFluxAbsorbed + totalFluxReflected
      math.abs(totalOutput - totalFluxEmitted) / totalFluxEmitted
    else 0.0

  def fresnelTransmission: Double =
    if totalFluxEmitted > 0 then totalFluxDeposited / totalFluxEmitted else 0.0

/** Image and ray-statistics result for one render.
  *
  * @param image row-major RGBA8 image bytes, length `width * height * 4`
  * @param totalRays total rays traced during the frame
  * @param primaryRays camera rays launched from the image plane
  * @param reflectedRays recursive reflection rays
  * @param refractedRays recursive transmission/refraction rays
  * @param shadowRays visibility rays used for direct-light shadow testing
  * @param aaRays extra adaptive antialiasing rays
  * @param aaStackOverflows adaptive antialiasing stack overflow count
  * @param maxDepthReached deepest recursive ray depth reached
  * @param minDepthReached shallowest recursive ray depth reached
  * @param frameMs elapsed frame time in milliseconds
  */
case class RenderResult(
  image: Array[Byte],
  totalRays: Long,
  primaryRays: Long,
  reflectedRays: Long,
  refractedRays: Long,
  shadowRays: Long,
  aaRays: Long,
  aaStackOverflows: Long,
  maxDepthReached: Int,
  minDepthReached: Int,
  frameMs: Float
):
  def stats: RayStats = RayStats(
    totalRays,
    primaryRays,
    reflectedRays,
    refractedRays,
    shadowRays,
    aaRays,
    aaStackOverflows,
    maxDepthReached,
    minDepthReached,
    frameMs
  )

/** Axis-aligned clipping or ground plane description.
  *
  * `axis` uses native axis ids (`0 = x`, `1 = y`, `2 = z`). `positive` selects
  * the positive-facing side and `value` is the plane offset along that axis.
  */
case class PlaneSpec(axis: Int, positive: Boolean, value: Float)

/** Two-color checker pattern used by plane helpers. */
case class CheckerPattern(color1: Color, color2: Color)

/** High-level Scala facade for the native OptiX renderer.
  *
  * A renderer owns one native `OptiXWrapper` handle after [[initialize]] succeeds.
  * Call [[dispose]] when the renderer is no longer needed; calling [[initialize]]
  * more than once is idempotent, and calling [[reinitialize]] disposes the old
  * handle before creating a new one.
  *
  * Typical render flow: construct the renderer, call [[initialize]], upload scene
  * geometry and material state, configure camera/lights/render options, call
  * [[renderWithStats]] or [[render]], then call [[dispose]]. Scene units are
  * application-defined world units. Angles are degrees unless a method name
  * explicitly describes a 4D rotation parameter.
  */
class OptiXRenderer
  extends OptiXSphereApi
  with OptiXMeshApi
  with OptiXPlaneApi
  with OptiXTextureApi
  with OptiXRenderApi
  with LazyLogging:

  // Native handle to the C++ OptiXWrapper instance (0 = not initialized)
  // Note: Must be accessible to JNI (not private) - JNI reads/writes this field directly
  // This var is required by the JNI handle pattern - no functional alternative exists
  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  @volatile var nativeHandle: Long = 0L

  // Derive initialization state from nativeHandle (0 = not initialized)
  private def isInitialized: Boolean = nativeHandle != 0L

  // ---- Lifecycle @native declarations ----
  @native private def initializeNative(maxInstances: Int): Boolean
  @native private def disposeNative(): Unit

  // ---- Single-sphere legacy @native declarations ----
  @native private[optix] def setSphere(x: Float, y: Float, z: Float, radius: Float): Unit
  @native private[optix] def setSphereColorNative(r: Float, g: Float, b: Float, a: Float): Unit

  // ---- Camera/scene @native declarations ----
  @native private def setCameraNative(eye: Array[Float], lookAt: Array[Float], up: Array[Float], horizontalFovDegrees: Float): Unit
  /** Sets native render target dimensions in pixels. */
  @native def updateImageDimensions(width: Int, height: Int): Unit

  /** Sets the legacy single-sphere index of refraction. */
  @native def setIOR(ior: Float): Unit

  /** Sets the legacy single-sphere scale multiplier. */
  @native def setScale(scale: Float): Unit

  // ---- Lights @native declarations ----
  @native private def setLight(direction: Array[Float], intensity: Float): Unit
  @native private def setLights(lights: Array[Light]): Unit
  /** Enables or disables direct-light shadow rays. */
  @native def setShadows(enabled: Boolean): Unit
  @native private def setTransparentShadowsNative(enabled: Boolean): Unit
  @native private def setBackgroundColorNative(r: Float, g: Float, b: Float): Unit
  @native private def setFogNative(density: Float, r: Float, g: Float, b: Float): Unit
  @native private[optix] def setToneMappingNative(operatorId: Int, exposure: Float): Unit
  @native private[optix] def setIBLNative(enabled: Boolean, strength: Float, samples: Int): Unit
  @native private[optix] def setAccumulationFramesNative(n: Int): Unit

  // ---- Texture @native declarations (called from OptiXTextureApi) ----
  @native private[optix] def setEnvironmentMapNative(textureIndex: Int): Unit
  @native private[optix] def setProceduralTextureNative(instanceId: Int, proceduralType: Int, proceduralScale: Float): Unit
  @native private[optix] def setMapTexturesNative(instanceId: Int, normalTextureIndex: Int, roughnessTextureIndex: Int): Unit
  @native private[optix] def setImageTextureNative(instanceId: Int, imageTextureIndex: Int): Unit
  @native private[optix] def uploadTextureNative(name: String, imageData: Array[Byte], width: Int, height: Int): Int
  @native private[optix] def uploadTextureFromFileNative(path: String): Int
  @native private[optix] def releaseTexturesNative(): Unit

  // ---- Render @native declarations ----
  /** Configures adaptive antialiasing.
    *
    * @param maxDepth maximum adaptive subdivision depth
    * @param threshold color-difference threshold that triggers extra samples
    */
  @native def setAntialiasing(enabled: Boolean, maxDepth: Int, threshold: Float): Unit

  /** Sets the maximum recursive reflection/refraction ray depth. */
  @native def setMaxRayDepth(depth: Int): Unit

  /** Configures progressive photon-mapping caustics.
    *
    * @param photonsPerIter photons emitted per iteration
    * @param iterations number of photon-map iterations
    * @param initialRadius initial photon gather radius in world units
    * @param alpha progressive radius reduction coefficient
    */
  @native def setCaustics(
    enabled: Boolean,
    photonsPerIter: Int,
    iterations: Int,
    initialRadius: Float,
    alpha: Float
  ): Unit
  @native private[optix] def getCausticsStatsNative(): CausticsStats

  /** Renders an RGBA8 frame and native ray statistics for the given dimensions.
    *
    * Current native error paths may return `null`; Task 26.4 tracks replacing
    * that contract with `Option[RenderResult]`.
    */
  @native def renderWithStats(width: Int, height: Int): RenderResult

  // ---- Plane @native declarations (called from OptiXPlaneApi) ----
  @native private[optix] def clearPlanesNative(): Unit
  @native private[optix] def addPlaneNative(axis: Int, positive: Boolean, value: Float): Unit
  @native private[optix] def addPlaneSolidColorNative(
    axis: Int, positive: Boolean, value: Float, r: Float, g: Float, b: Float): Unit
  @native private[optix] def addPlaneCheckerColorsNative(
    axis: Int, positive: Boolean, value: Float,
    r1: Float, g1: Float, b1: Float, r2: Float, g2: Float, b2: Float): Unit
  @native private[optix] def addPlaneSolidColorWithMaterialNative(
    axis: Int, positive: Boolean, value: Float,
    r: Float, g: Float, b: Float,
    roughness: Float, metallic: Float, specular: Float, emission: Float,
    textureIndex: Int): Unit
  @native private[optix] def addPlaneCheckerColorsWithMaterialNative(
    axis: Int, positive: Boolean, value: Float,
    r1: Float, g1: Float, b1: Float, r2: Float, g2: Float, b2: Float,
    roughness: Float, metallic: Float, specular: Float, emission: Float,
    textureIndex: Int): Unit

  // ---- Triangle mesh @native declarations (called from OptiXMeshApi) ----
  @native private[optix] def setTriangleMeshNative(
    vertices: Array[Float],
    numVertices: Int,
    indices: Array[Int],
    numTriangles: Int,
    vertexStride: Int
  ): Unit

  @native private[optix] def setProjectedMeshNative(
    facesData: Array[Float],
    numFaces: Int,
    vertsPerFace: Int,
    uvs: Array[Float],         // null permitted
    eyeW: Float,
    screenW: Float,
    rotXW: Float,
    rotYW: Float,
    rotZW: Float,
    centerX: Float,
    centerY: Float,
    centerZ: Float
  ): Int

  @native private[optix] def updateMesh4DProjectionNative(
    meshIndex: Int,
    eyeW: Float,
    screenW: Float,
    rotXW: Float,
    rotYW: Float,
    rotZW: Float,
    centerX: Float,
    centerY: Float,
    centerZ: Float
  ): Int

  @native private[optix] def setTriangleMeshColorNative(r: Float, g: Float, b: Float, a: Float): Unit
  /** Sets the legacy single triangle-mesh index of refraction. */
  @native def setTriangleMeshIOR(ior: Float): Unit

  /** Removes the legacy single triangle mesh from native scene state. */
  @native def clearTriangleMesh(): Unit

  /** Returns whether legacy single triangle-mesh state is present. */
  @native def hasTriangleMesh(): Boolean

  @native private[optix] def updateCpuTriangleMeshNative(
    meshIndex: Int,
    vertices: Array[Float], numVertices: Int,
    indices: Array[Int], numTriangles: Int,
    vertexStride: Int
  ): Int

  // ---- IAS instance @native declarations (called from OptiXMeshApi / OptiXPlaneApi) ----
  @native private[optix] def addSphereInstanceNative(
    transform: Array[Float],
    r: Float, g: Float, b: Float, a: Float,
    ior: Float, roughness: Float, metallic: Float, specular: Float, emission: Float,
    filmThickness: Float
  ): Int

  @native private[optix] def addTriangleMeshInstanceNative(
    transform: Array[Float],
    r: Float, g: Float, b: Float, a: Float,
    ior: Float, roughness: Float, metallic: Float, specular: Float, emission: Float,
    textureIndex: Int,
    filmThickness: Float
  ): Int

  @native private[optix] def addCylinderInstanceNative(
    p0_x: Float, p0_y: Float, p0_z: Float,
    p1_x: Float, p1_y: Float, p1_z: Float,
    radius: Float,
    r: Float, g: Float, b: Float, a: Float,
    ior: Float, roughness: Float, metallic: Float, specular: Float, emission: Float,
    filmThickness: Float
  ): Int

  @native private[optix] def addConeInstanceNative(
    apex_x: Float, apex_y: Float, apex_z: Float,
    base_x: Float, base_y: Float, base_z: Float,
    radius: Float,
    r: Float, g: Float, b: Float, a: Float,
    ior: Float, roughness: Float, metallic: Float, specular: Float, emission: Float,
    filmThickness: Float
  ): Int

  @native private[optix] def addRecursiveIASSpongeInstanceNative(
    level: Int,
    transform: Array[Float],
    r: Float, g: Float, b: Float, a: Float,
    ior: Float, roughness: Float, metallic: Float, specular: Float, emission: Float,
    textureIndex: Int,
    filmThickness: Float
  ): Int

  @native private[optix] def addMenger4DInstanceNative(
    level: Int,
    distanceThreshold: Int,
    x: Float, y: Float, z: Float, scale: Float,
    eyeW: Float, screenW: Float,
    rotXW: Float, rotYW: Float, rotZW: Float,
    r: Float, g: Float, b: Float, a: Float,
    ior: Float, roughness: Float, metallic: Float, specular: Float, emission: Float,
    filmThickness: Float
  ): Int

  @native private[optix] def updateMenger4DProjectionNative(
    instanceId: Int,
    eyeW: Float, screenW: Float,
    rotXW: Float, rotYW: Float, rotZW: Float
  ): Int

  @native private[optix] def addSierpinski4DInstanceNative(
    level: Int,
    x: Float, y: Float, z: Float,
    scale: Float, eyeW: Float, screenW: Float,
    rotXW: Float, rotYW: Float, rotZW: Float,
    r: Float, g: Float, b: Float, a: Float,
    ior: Float, roughness: Float, metallic: Float,
    specular: Float, emission: Float, filmThickness: Float
  ): Int

  @native private[optix] def updateSierpinski4DProjectionNative(
    instanceId: Int,
    eyeW: Float, screenW: Float,
    rotXW: Float, rotYW: Float, rotZW: Float
  ): Int

  @native private[optix] def addHexadecachoron4DInstanceNative(
    level: Int,
    x: Float, y: Float, z: Float,
    scale: Float, eyeW: Float, screenW: Float,
    rotXW: Float, rotYW: Float, rotZW: Float,
    r: Float, g: Float, b: Float, a: Float,
    ior: Float, roughness: Float, metallic: Float,
    specular: Float, emission: Float, filmThickness: Float
  ): Int

  @native private[optix] def updateHexadecachoron4DProjectionNative(
    instanceId: Int,
    eyeW: Float, screenW: Float,
    rotXW: Float, rotYW: Float, rotZW: Float
  ): Int

  /** Removes one IAS instance by id. Invalid ids are ignored by native code. */
  @native def removeInstance(instanceId: Int): Unit

  /** Removes all IAS instances from native scene state. */
  @native def clearAllInstances(): Unit

  /** Returns the number of currently active IAS instances. */
  @native def getInstanceCount(): Int

  /** Returns whether native rendering uses IAS instance mode. */
  @native def isIASMode(): Boolean

  /** Enables or disables native IAS instance mode. */
  @native def setIASMode(enabled: Boolean): Unit

  @native private[optix] def addPlaneInstanceNative(
    normal_x: Float, normal_y: Float, normal_z: Float,
    distance: Float,
    r: Float, g: Float, b: Float, a: Float, ior: Float,
    roughness: Float, metallic: Float, specular: Float, emission: Float,
    filmThickness: Float,
    r2: Float, g2: Float, b2: Float,
    solidColor: Int, checkerSize: Float
  ): Int

  // ---- Camera ----
  /** Sets the camera in world space.
    *
    * @param eye camera position
    * @param lookAt target point
    * @param up camera up vector
    * @param horizontalFovDegrees horizontal field of view in `(0, 180)` degrees
    */
  def setCamera(eye: Vector[3], lookAt: Vector[3], up: Vector[3], horizontalFovDegrees: Float): Unit =
    require(
      horizontalFovDegrees > 0 && horizontalFovDegrees < 180,
      s"Horizontal FOV must be in range (0, 180), got $horizontalFovDegrees"
    )
    setCameraNative(eye.toArray, lookAt.toArray, up.toArray, horizontalFovDegrees)

  /** Sets native render target dimensions from an [[menger.common.ImageSize]]. */
  def updateImageDimensions(size: ImageSize): Unit =
    updateImageDimensions(size.width, size.height)

  // ---- Lights ----
  /** Sets one directional light for backward-compatible callers.
    *
    * @param direction light direction vector in world space
    * @param intensity linear light intensity multiplier
    */
  def setLight(direction: Vector[3], intensity: Float): Unit =
    setLight(direction.toArray, intensity)

  /** Replaces the native light list.
    *
    * Supports directional, point, and disk-area lights from `menger.common.Light`.
    */
  def setLights(lights: Array[CommonLight]): Unit =
    val jniLights = lights.map(toJNILight)
    setLights(jniLights)

  /** Enables Beer-Lambert attenuation for transparent shadow rays. */
  def setTransparentShadows(enabled: Boolean): Unit =
    setTransparentShadowsNative(enabled)

  /** Sets linear RGB background color used by miss shaders. */
  def setBackgroundColor(r: Float, g: Float, b: Float): Unit =
    setBackgroundColorNative(r, g, b)

  /** Sets exponential fog density and linear RGB fog color. */
  def setFog(density: Float, r: Float, g: Float, b: Float): Unit =
    setFogNative(density, r, g, b)

  // ---- Lifecycle ----
  /** Initializes the native OptiX wrapper if it is not already initialized.
    *
    * The method is idempotent: after a successful initialization, later calls
    * return `true` without creating another native handle. `maxInstances` sets
    * the initial IAS instance capacity.
    */
  def initialize(maxInstances: Int = 64): Boolean =
    if isInitialized then
      true  // Already initialized, return success
    else
      val result = initializeNative(maxInstances)
      if !result then
        logger.error("Failed to initialize OptiX renderer")
      result

  /** Reinitializes the renderer with a new IAS instance capacity.
    *
    * Disposes the current native handle, if one exists, and creates a new one.
    * Use this when scene analysis determines a higher instance limit is needed.
    */
  def reinitialize(newMaxInstances: Int): Boolean =
    if isInitialized then
      dispose()
    initialize(newMaxInstances)

  /** Releases native resources owned by this renderer.
    *
    * The renderer can be initialized again after disposal. Calling `dispose` on
    * an uninitialized renderer is a no-op.
    */
  def dispose(): Unit =
    if isInitialized then
      disposeNative()

  /** Returns whether the native library loads and OptiX initialization succeeds. */
  def isAvailable: Boolean =
    Try(initialize()).recover:
      case e: UnsatisfiedLinkError =>
        logger.warn("OptiX native library not available", e)
        false
      case e: Exception =>
        logger.error("Error checking OptiX availability", e)
        false
    .getOrElse(false)

  /** Ensures OptiX is available and returns this renderer. Throws [[OptiXNotAvailableException]] on failure. */
  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  def ensureAvailable(): OptiXRenderer =
    if !OptiXRenderer.isLibraryLoaded then
      throw OptiXNotAvailableException(
        "OptiX native library failed to load - ensure CUDA and OptiX are available"
      )
    else if !initialize() then
      throw OptiXNotAvailableException("Failed to initialize OptiX renderer")
    else
      this

/** Native-library loader and availability helpers for [[OptiXRenderer]]. */
object OptiXRenderer extends LazyLogging:
  private val libraryName = "optixjni"

  private val libraryLoaded: Boolean = loadNativeLibrary().isSuccess

  /** Returns whether `liboptixjni.so` was loaded from `java.library.path` or the classpath. */
  def isLibraryLoaded: Boolean = libraryLoaded

  // Functional helper methods for library loading
  private def loadFromSystemPath(): Try[Unit] =
    for
      _ <- catching(classOf[UnsatisfiedLinkError]).withTry:
             System.loadLibrary(libraryName)
             logger.info(s"Loaded $libraryName from java.library.path")
      platform <- detectPlatform()
      _ <- extractPTX(platform)
    yield ()

  private def detectPlatform(): Try[String] =
    val os = System.getProperty("os.name").toLowerCase
    val arch = System.getProperty("os.arch").toLowerCase
    (os, arch) match
      case (o, a) if o.contains("linux") && (a.contains("amd64") || a.contains("x86_64")) =>
        Success("x86_64-linux")
      case _ =>
        Failure(new UnsupportedOperationException(s"Unsupported platform: $os/$arch"))

  private def copyStreamToFile(stream: InputStream, out: FileOutputStream): Try[Unit] = Try:
    val buffer = new Array[Byte](8192)
    @scala.annotation.tailrec
    def copyLoop(): Unit =
      stream.read(buffer) match
        case -1 => // done
        case bytesRead =>
          out.write(buffer, 0, bytesRead)
          copyLoop()
    copyLoop()

  private def extractPTX(platform: String): Try[Unit] = Try:
    val ptxResourcePath = s"/native/$platform/optix_shaders.ptx"
    Option(getClass.getResourceAsStream(ptxResourcePath)) match
      case Some(ptxStream) =>
        val ptxDir = new java.io.File("target/native/x86_64-linux/bin")
        ptxDir.mkdirs()
        val ptxFile = new java.io.File(ptxDir, "optix_shaders.ptx")
        val ptxOut = new FileOutputStream(ptxFile)
        try
          copyStreamToFile(ptxStream, ptxOut).get
          logger.debug(s"Extracted PTX file to: ${ptxFile.getAbsolutePath}")
        finally
          ptxOut.close()
          ptxStream.close()
      case None =>
        logger.debug(s"PTX resource not found: $ptxResourcePath")

  private def extractAndLoadLibrary(stream: InputStream, resourcePath: String): Try[Unit] = Try:
    val tempFile = Files.createTempFile(s"lib$libraryName", ".so")
    tempFile.toFile.deleteOnExit()
    val out = new FileOutputStream(tempFile.toFile)
    try
      copyStreamToFile(stream, out).get
    finally
      out.close()
      stream.close()
    System.load(tempFile.toAbsolutePath.toString)
    logger.debug(s"Loaded $libraryName from classpath via temp file: ${tempFile.toAbsolutePath}")

  private def loadFromClasspath(): Try[Unit] =
    for
      platform <- detectPlatform()
      resourcePath = s"/native/$platform/lib$libraryName.so"
      _ = logger.debug(s"Attempting to load library from resource: $resourcePath")
      stream <- Option(getClass.getResourceAsStream(resourcePath))
        .toRight(new IllegalStateException(s"Library resource not found: $resourcePath"))
        .toTry
      _ <- extractAndLoadLibrary(stream, resourcePath)
      _ <- extractPTX(platform)
    yield ()

  private def loadNativeLibrary(): Try[Unit] =
    loadFromSystemPath().recoverWith:
      case _: UnsatisfiedLinkError =>
        logger.debug(s"Failed to load $libraryName from java.library.path, trying classpath")
        loadFromClasspath()
    .recoverWith:
      case e: Exception =>
        logger.error(s"Failed to load native library '$libraryName'", e)
        Failure(e)

/** Raised by [[OptiXRenderer.ensureAvailable]] when native OptiX cannot be used. */
case class OptiXNotAvailableException(message: String) extends Exception(message)

/** Procedural texture ids passed to [[OptiXTextureApi.setProceduralTexture]]. */
object ProceduralType:
  val None         = 0
  val ValueNoise   = 1
  val FBM          = 2
  val Worley       = 3
  val Gradient     = 4
  val Wood         = 5
  val Marble       = 6
  val LayeredNoise = 7
  val XYZToRGB     = 8
  val HeatMap      = 9
  val Triplanar    = 10
/** Raised when [[OptiXTextureApi.uploadTexture]] receives a negative native result code. */
case class TextureUploadException(message: String, cause: Throwable = null) extends Exception(message, cause) // scalafix:ok DisableSyntax.null
