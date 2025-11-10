package menger.optix

import com.typesafe.scalalogging.LazyLogging

import java.io.{FileOutputStream, InputStream}
import java.nio.file.Files
import scala.util.{Failure, Success, Try}
import scala.util.control.Exception.catching

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

  /** Set sphere geometry (center position and radius) */
  @native def setSphere(x: Float, y: Float, z: Float, radius: Float): Unit

  /** Set sphere material color (RGBA, 0.0-1.0, alpha: 0.0=transparent, 1.0=opaque) */
  @native def setSphereColor(r: Float, g: Float, b: Float, a: Float): Unit

  /** Set index of refraction (1.0=no refraction, 1.5=glass, 1.33=water, 2.42=diamond) */
  @native def setIOR(ior: Float): Unit

  /** Set physical scale factor (1.0=meters, 0.01=centimeters) */
  @native def setScale(scale: Float): Unit

  /** Set camera parameters (eye position, lookAt point, up vector, field of view in degrees) */
  @native def setCamera(eye: Array[Float], lookAt: Array[Float], up: Array[Float], fov: Float): Unit

  /** Update image dimensions for aspect ratio calculations (called before setCamera on resize) */
  @native def updateImageDimensions(width: Int, height: Int): Unit

  /** Set directional light (normalized direction vector, intensity multiplier) */
  @native def setLight(direction: Array[Float], intensity: Float): Unit

  /** Set clipping plane (axis: 0=X 1=Y 2=Z, positive: plane normal direction, value: position) */
  @native def setPlane(axis: Int, positive: Boolean, value: Float): Unit

  /** Render scene and return RGBA image data (width × height × 4 bytes) */
  @native def render(width: Int, height: Int): Array[Byte]

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
