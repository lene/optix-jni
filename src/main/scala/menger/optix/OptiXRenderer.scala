package menger.optix

import com.typesafe.scalalogging.LazyLogging
import menger.common.{ImageSize, Vector, x, y, z}
import menger.common.toArray

import java.io.{FileOutputStream, InputStream}
import java.nio.file.Files
import scala.util.{Failure, Success, Try}
import scala.util.control.Exception.catching

import menger.common.{Light => CommonLight}

// Light source types (must match C++ LightType enum)
private object LightTypeJNI:
  val DIRECTIONAL: Int = 0  // Parallel rays from infinity (sun-like), no distance attenuation
  val POINT: Int = 1         // Radiate from position, inverse-square falloff

// Package-private case class for JNI boundary (matches C++ expectations)
// Must be accessible to JNI (not private) but kept in menger.optix package
// The C++ code looks for "menger/optix/Light" class
case class Light(
  lightType: Int,
  direction: Array[Float],
  position: Array[Float],
  color: Array[Float],
  intensity: Float
)

// Private helper to convert common.Light to JNI representation
private def toJNILight(light: CommonLight): Light =
  light match
    case CommonLight.Directional(dir, color, intensity) =>
      Light(
        lightType = LightTypeJNI.DIRECTIONAL,
        direction = dir.toArray,
        position = Array(0f, 0f, 0f),  // Unused for directional
        color = color.toArray,
        intensity = intensity
      )
    case CommonLight.Point(pos, color, intensity) =>
      Light(
        lightType = LightTypeJNI.POINT,
        direction = Array(0f, 0f, 0f),  // Unused for point
        position = pos.toArray,
        color = color.toArray,
        intensity = intensity
      )

// Ray statistics from OptiX rendering
case class RayStats(
  totalRays: Long,
  primaryRays: Long,
  reflectedRays: Long,
  refractedRays: Long,
  shadowRays: Long,
  aaRays: Long,
  maxDepthReached: Int,
  minDepthReached: Int
)

// Combined result from rendering with statistics
case class RenderResult(
  image: Array[Byte],
  totalRays: Long,
  primaryRays: Long,
  reflectedRays: Long,
  refractedRays: Long,
  shadowRays: Long,
  aaRays: Long,
  maxDepthReached: Int,
  minDepthReached: Int
):
  def stats: RayStats = RayStats(
    totalRays,
    primaryRays,
    reflectedRays,
    refractedRays,
    shadowRays,
    aaRays,
    maxDepthReached,
    minDepthReached
  )

// JNI interface to OptiX ray tracing renderer
// Phase 1: Basic structure with placeholder implementations
// Phase 2: Actual OptiX context and data structure implementation
// Phase 3: Full pipeline and shader implementation
// Phase 4: Complete integration and testing
class OptiXRenderer extends LazyLogging:

  // Native handle to the C++ OptiXWrapper instance (0 = not initialized)
  // Note: Must be accessible to JNI (not private)
  @volatile var nativeHandle: Long = 0L

  // Track initialization state to ensure idempotence
  private var initialized: Boolean = false

  // Native method declarations (private - use public wrappers)
  @native private def initializeNative(): Boolean
  @native private def disposeNative(): Unit


  @native private def setSphere(x: Float, y: Float, z: Float, radius: Float): Unit

  def setSphere(center: Vector[3], radius: Float): Unit =
    setSphere(center.x, center.y, center.z, radius)

  
  @native def setSphereColor(r: Float, g: Float, b: Float, a: Float): Unit

  
  @native def setIOR(ior: Float): Unit

  
  @native def setScale(scale: Float): Unit


  @native private def setCameraNative(eye: Array[Float], lookAt: Array[Float], up: Array[Float], horizontalFovDegrees: Float): Unit

  def setCamera(eye: Vector[3], lookAt: Vector[3], up: Vector[3], horizontalFovDegrees: Float): Unit =
    require(
      horizontalFovDegrees > 0 && horizontalFovDegrees < 180,
      s"Horizontal FOV must be in range (0, 180), got $horizontalFovDegrees"
    )
    setCameraNative(eye.toArray, lookAt.toArray, up.toArray, horizontalFovDegrees)


  @native def updateImageDimensions(width: Int, height: Int): Unit

  def updateImageDimensions(size: ImageSize): Unit =
    updateImageDimensions(size.width, size.height)


  @native private def setLight(direction: Array[Float], intensity: Float): Unit

  def setLight(direction: Vector[3], intensity: Float): Unit =
    setLight(direction.toArray, intensity)

  @native private def setLights(lights: Array[Light]): Unit

  def setLights(lights: Seq[CommonLight]): Unit =
    val jniLights = lights.map(toJNILight).toArray
    setLights(jniLights)

  
  @native def setShadows(enabled: Boolean): Unit


  @native def setAntialiasing(enabled: Boolean, maxDepth: Int, threshold: Float): Unit


  @native def setPlaneSolidColor(solid: Boolean): Unit

  
  @native def setPlane(axis: Int, positive: Boolean, value: Float): Unit


  @native def renderWithStats(width: Int, height: Int): RenderResult

  def renderWithStats(size: ImageSize): RenderResult =
    renderWithStats(size.width, size.height)


  def render(width: Int, height: Int): Option[Array[Byte]] =
    Option(renderWithStats(width, height)).map(_.image)

  def render(size: ImageSize): Option[Array[Byte]] =
    render(size.width, size.height)

  // Idempotent initialization - safe to call multiple times
  def initialize(): Boolean =
    if initialized then
      true  // Already initialized, return success
    else
      val result = initializeNative()
      if result then
        initialized = true
      else
        logger.error("Failed to initialize OptiX renderer")
      result

  // Can be re-initialized after dispose by calling initialize() again
  def dispose(): Unit =
    if initialized then
      disposeNative()
      initialized = false

  // Convenience method with default alpha parameter for backward compatibility
  def setSphereColor(r: Float, g: Float, b: Float): Unit =
    setSphereColor(r, g, b, 1.0f)

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

  private def detectPlatform(): Try[String] = Try:
    val os = System.getProperty("os.name").toLowerCase
    val arch = System.getProperty("os.arch").toLowerCase
    (os, arch) match
      case (o, a) if o.contains("linux") && (a.contains("amd64") || a.contains("x86_64")) =>
        "x86_64-linux"
      case _ =>
        throw new UnsupportedOperationException(s"Unsupported platform: $os/$arch")

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
    val ptxResourcePath = s"/native/$platform/sphere_combined.ptx"
    Option(getClass.getResourceAsStream(ptxResourcePath)) match
      case Some(ptxStream) =>
        val ptxDir = new java.io.File("target/native/x86_64-linux/bin")
        ptxDir.mkdirs()
        val ptxFile = new java.io.File(ptxDir, "sphere_combined.ptx")
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
