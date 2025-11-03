package menger.optix

import com.typesafe.scalalogging.LazyLogging

import java.io.{FileOutputStream, InputStream}
import java.nio.file.Files
import scala.util.{Failure, Try}

/**
 * JNI interface to OptiX ray tracing renderer.
 *
 * This class provides a Scala interface to native OptiX functionality
 * for rendering spheres and other geometry with hardware-accelerated
 * ray tracing.
 *
 * Phase 1: Basic structure with placeholder implementations
 * Phase 2: Actual OptiX context and data structure implementation
 * Phase 3: Full pipeline and shader implementation
 * Phase 4: Complete integration and testing
 */
class OptiXRenderer extends LazyLogging:

  // Native method declarations
  @native def initialize(): Boolean
  @native def setSphere(x: Float, y: Float, z: Float, radius: Float): Unit
  @native def setSphereColor(r: Float, g: Float, b: Float, a: Float): Unit
  @native def setIOR(ior: Float): Unit
  @native def setScale(scale: Float): Unit
  @native def setCamera(eye: Array[Float], lookAt: Array[Float], up: Array[Float], fov: Float): Unit
  @native def setLight(direction: Array[Float], intensity: Float): Unit
  @native def render(width: Int, height: Int): Array[Byte]
  @native def dispose(): Unit

  // Convenience method with default alpha parameter for backward compatibility
  def setSphereColor(r: Float, g: Float, b: Float): Unit =
    setSphereColor(r, g, b, 1.0f)

  /**
   * Check if OptiX is available on this system.
   * @return true if OptiX can be initialized
   */
  def isAvailable: Boolean =
    Try(initialize()).recover:
      case e: UnsatisfiedLinkError =>
        logger.warn("OptiX native library not available", e)
        false
      case e: Exception =>
        logger.error("Error checking OptiX availability", e)
        false
    .getOrElse(false)

  def ensureAvailable(): OptiXRenderer =
    if !OptiXRenderer.isLibraryLoaded then
      ErrorHandling.errorExit(
        "OptiX native library failed to load - ensure CUDA and OptiX are available"
      )
    if !isAvailable then
      ErrorHandling.errorExit(
        "OptiX not available on this system - ensure CUDA and OptiX are available"
      )
    if !initialize() then
      ErrorHandling.errorExit("Failed to initialize OptiX renderer")
    this

object OptiXRenderer extends LazyLogging:
  private val libraryName = "optixjni"

  private val libraryLoaded: Boolean = {
    try {
      System.loadLibrary(libraryName)
      logger.info(s"Loaded $libraryName from java.library.path")
      true
    } catch {
      case e: UnsatisfiedLinkError =>
        logger.debug(s"Failed to load $libraryName from java.library.path: ${e.getMessage}")
        logger.debug(s"Attempting to load from classpath...")
        try {
          val platform = "x86_64-linux"
          val resourcePath = s"/native/$platform/lib$libraryName.so"
          val stream = Option(getClass.getResourceAsStream(resourcePath))
            .getOrElse(throw new IllegalStateException(s"Library resource not found: $resourcePath"))

          val tempFile = Files.createTempFile(s"lib$libraryName", ".so")
          tempFile.toFile.deleteOnExit()

          val out = new FileOutputStream(tempFile.toFile)
          try {
            val buffer = new Array[Byte](8192)
            var bytesRead = stream.read(buffer)
            while (bytesRead != -1) {
              out.write(buffer, 0, bytesRead)
              bytesRead = stream.read(buffer)
            }
          } finally {
            out.close()
            stream.close()
          }

          System.load(tempFile.toAbsolutePath.toString)
          logger.info(s"Loaded $libraryName from classpath via temp file: ${tempFile.toAbsolutePath}")

          // Also extract PTX file to expected location
          val ptxResourcePath = s"/native/$platform/sphere_combined.ptx"
          Option(getClass.getResourceAsStream(ptxResourcePath)).foreach { ptxStream =>
            val ptxDir = new java.io.File("target/native/x86_64-linux/bin")
            ptxDir.mkdirs()
            val ptxFile = new java.io.File(ptxDir, "sphere_combined.ptx")
            val ptxOut = new FileOutputStream(ptxFile)
            try {
              val buffer = new Array[Byte](8192)
              var bytesRead = ptxStream.read(buffer)
              while (bytesRead != -1) {
                ptxOut.write(buffer, 0, bytesRead)
                bytesRead = ptxStream.read(buffer)
              }
              logger.debug(s"Extracted PTX file to: ${ptxFile.getAbsolutePath}")
            } finally {
              ptxOut.close()
              ptxStream.close()
            }
          }

          true
        } catch {
          case e: Exception =>
            logger.error(s"Failed to load native library '$libraryName'", e)
            false
        }
    }
  }

  private def loadFromSystemPath(): Try[Unit] = Try:
    System.loadLibrary(libraryName)
    logger.info(s"Loaded $libraryName from java.library.path")

  private def detectPlatform(): Try[String] = Try:
    val os = System.getProperty("os.name").toLowerCase
    val arch = System.getProperty("os.arch").toLowerCase

    (os, arch) match
      case (o, a) if o.contains("linux") && (a.contains("amd64") || a.contains("x86_64")) => "x86_64-linux"
      case _ => throw new UnsupportedOperationException(s"Unsupported platform: $os/$arch")

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

  private def loadFromClasspath(): Try[Unit] =
    for
      platform <- detectPlatform()
      resourcePath = s"/native/$platform/lib$libraryName.so"
      _ = logger.debug(s"Attempting to load library from resource: $resourcePath")
      stream <- Option(getClass.getResourceAsStream(resourcePath))
        .toRight(new IllegalStateException(s"Library resource not found: $resourcePath"))
        .toTry
      result <- extractAndLoadLibrary(stream, resourcePath)
    yield result

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
    logger.info(s"Loaded $libraryName from classpath resource via temp file: ${tempFile.toAbsolutePath}")

  private def loadNativeLibrary(): Try[Unit] =
    loadFromSystemPath().recoverWith:
      case _: UnsatisfiedLinkError =>
        logger.debug(s"Failed to load $libraryName from java.library.path, trying classpath")
        loadFromClasspath()
    .recoverWith:
      case e: UnsatisfiedLinkError =>
        logger.error(s"Failed to load native library '$libraryName'", e)
        Failure(e)
      case e: Exception =>
        logger.error(s"Unexpected error loading native library '$libraryName'", e)
        Failure(e)

  def isLibraryLoaded: Boolean = libraryLoaded
