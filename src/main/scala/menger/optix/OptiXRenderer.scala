package menger.optix

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

// Area light shape (must match C++ AreaLightShape enum)
object AreaLightShape:
  val DISK: Int = 0  // Circular disk emitter (only shape currently supported)

// Package-private case class for JNI boundary (matches C++ expectations)
// Must be accessible to JNI (not private) but kept in menger.optix package
// The C++ code looks for "menger/optix/Light" class
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

// Ray statistics from OptiX rendering
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

// Caustics statistics for validation (C1-C8 test ladder)
// Matches CausticsStats struct in OptiXData.h
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

// Combined result from rendering with statistics
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

case class PlaneSpec(axis: Int, positive: Boolean, value: Float)
case class CheckerPattern(color1: Color, color2: Color)

// JNI interface to OptiX ray tracing renderer
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
  @native def updateImageDimensions(width: Int, height: Int): Unit
  @native def setIOR(ior: Float): Unit
  @native def setScale(scale: Float): Unit

  // ---- Lights @native declarations ----
  @native private def setLight(direction: Array[Float], intensity: Float): Unit
  @native private def setLights(lights: Array[Light]): Unit
  @native def setShadows(enabled: Boolean): Unit
  @native private def setTransparentShadowsNative(enabled: Boolean): Unit
  @native private def setBackgroundColorNative(r: Float, g: Float, b: Float): Unit

  // ---- Texture @native declarations (called from OptiXTextureApi) ----
  @native private[optix] def setEnvironmentMapNative(textureIndex: Int): Unit
  @native private[optix] def setProceduralTextureNative(instanceId: Int, proceduralType: Int, proceduralScale: Float): Unit
  @native private[optix] def setMapTexturesNative(instanceId: Int, normalTextureIndex: Int, roughnessTextureIndex: Int): Unit
  @native private[optix] def uploadTextureNative(name: String, imageData: Array[Byte], width: Int, height: Int): Int
  @native private[optix] def uploadTextureFromFileNative(path: String): Int
  @native private[optix] def releaseTexturesNative(): Unit

  // ---- Render @native declarations ----
  @native def setAntialiasing(enabled: Boolean, maxDepth: Int, threshold: Float): Unit
  @native def setMaxRayDepth(depth: Int): Unit
  @native def setCaustics(enabled: Boolean, photonsPerIter: Int, iterations: Int, initialRadius: Float, alpha: Float): Unit
  @native private[optix] def getCausticsStatsNative(): CausticsStats
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
  @native def setTriangleMeshIOR(ior: Float): Unit
  @native def clearTriangleMesh(): Unit
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

  @native def removeInstance(instanceId: Int): Unit
  @native def clearAllInstances(): Unit
  @native def getInstanceCount(): Int
  @native def isIASMode(): Boolean
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
  def setCamera(eye: Vector[3], lookAt: Vector[3], up: Vector[3], horizontalFovDegrees: Float): Unit =
    require(
      horizontalFovDegrees > 0 && horizontalFovDegrees < 180,
      s"Horizontal FOV must be in range (0, 180), got $horizontalFovDegrees"
    )
    setCameraNative(eye.toArray, lookAt.toArray, up.toArray, horizontalFovDegrees)

  def updateImageDimensions(size: ImageSize): Unit =
    updateImageDimensions(size.width, size.height)

  // ---- Lights ----
  def setLight(direction: Vector[3], intensity: Float): Unit =
    setLight(direction.toArray, intensity)

  def setLights(lights: Seq[CommonLight]): Unit =
    val jniLights = lights.map(toJNILight).toArray
    setLights(jniLights)

  def setTransparentShadows(enabled: Boolean): Unit =
    setTransparentShadowsNative(enabled)

  def setBackgroundColor(r: Float, g: Float, b: Float): Unit =
    setBackgroundColorNative(r, g, b)

  // ---- Lifecycle ----
  // Idempotent initialization - safe to call multiple times
  def initialize(maxInstances: Int = 64): Boolean =
    if isInitialized then
      true  // Already initialized, return success
    else
      val result = initializeNative(maxInstances)
      if !result then
        logger.error("Failed to initialize OptiX renderer")
      result

  /**
   * Reinitialize the renderer with a new maxInstances value.
   * Disposes the current renderer and creates a new one.
   * Use this when auto-adjustment determines a higher limit is needed.
   */
  def reinitialize(newMaxInstances: Int): Boolean =
    if isInitialized then
      dispose()
    initialize(newMaxInstances)

  // Can be re-initialized after dispose by calling initialize() again
  def dispose(): Unit =
    if isInitialized then
      disposeNative()

  def isAvailable: Boolean =
    Try(initialize()).recover:
      case e: UnsatisfiedLinkError =>
        logger.warn("OptiX native library not available", e)
        false
      case e: Exception =>
        logger.error("Error checking OptiX availability", e)
        false
    .getOrElse(false)

  // Ensures OptiX is available, returns Try with error details
  def ensureAvailable(): Try[OptiXRenderer] =
    if !OptiXRenderer.isLibraryLoaded then
      Failure(OptiXNotAvailableException(
        "OptiX native library failed to load - ensure CUDA and OptiX are available"
      ))
    else if !initialize() then
      Failure(OptiXNotAvailableException("Failed to initialize OptiX renderer"))
    else
      Success(this)

object OptiXRenderer extends LazyLogging:
  private val libraryName = "optixjni"

  private val libraryLoaded: Boolean = loadNativeLibrary().isSuccess

  def isLibraryLoaded: Boolean = libraryLoaded

  // Functional helper methods for library loading
  private def loadFromSystemPath(): Try[Unit] =
    catching(classOf[UnsatisfiedLinkError]).withTry:
      System.loadLibrary(libraryName)
      logger.info(s"Loaded $libraryName from java.library.path")

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

// Exception thrown when OptiX is not available
case class OptiXNotAvailableException(message: String) extends Exception(message)

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
case class TextureUploadException(message: String, cause: Throwable = null) extends Exception(message, cause) // scalafix:ok DisableSyntax.null
