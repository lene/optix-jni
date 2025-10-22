package menger.optix

import com.typesafe.scalalogging.LazyLogging
import java.io.{File, FileOutputStream}
import java.nio.file.{Files, Path}
import scala.util.Try

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
class OptiXRenderer extends LazyLogging {

  // Native method declarations
  @native def initialize(): Boolean
  @native def setSphere(x: Float, y: Float, z: Float, radius: Float): Unit
  @native def setCamera(eye: Array[Float], lookAt: Array[Float], up: Array[Float], fov: Float): Unit
  @native def setLight(direction: Array[Float], intensity: Float): Unit
  @native def render(width: Int, height: Int): Array[Byte]
  @native def dispose(): Unit

  /**
   * Check if OptiX is available on this system.
   * @return true if OptiX can be initialized
   */
  def isAvailable: Boolean = {
    try {
      initialize()
    } catch {
      case e: UnsatisfiedLinkError =>
        logger.warn("OptiX native library not available", e)
        false
      case e: Exception =>
        logger.error("Error checking OptiX availability", e)
        false
    }
  }
}

object OptiXRenderer extends LazyLogging {
  private val libraryName = "optixjni"
  private val libraryLoaded = loadNativeLibrary()

  private def loadNativeLibrary(): Boolean = {
    try {
      // Strategy 1: Try loading from java.library.path
      try {
        System.loadLibrary(libraryName)
        logger.info(s"Loaded $libraryName from java.library.path")
        return true
      } catch {
        case _: UnsatisfiedLinkError =>
          logger.debug(s"Failed to load $libraryName from java.library.path, trying classpath")
      }

      // Strategy 2: Try loading from classpath resources
      val os = System.getProperty("os.name").toLowerCase
      val arch = System.getProperty("os.arch").toLowerCase

      val platform = (os, arch) match {
        case (o, a) if o.contains("linux") && (a.contains("amd64") || a.contains("x86_64")) => "x86_64-linux"
        case _ => throw new UnsupportedOperationException(s"Unsupported platform: $os/$arch")
      }

      val resourcePath = s"/native/$platform/lib$libraryName.so"
      logger.debug(s"Attempting to load library from resource: $resourcePath")

      Option(getClass.getResourceAsStream(resourcePath)) match {
        case Some(stream) =>
          try {
            // Create temp file
            val tempFile = Files.createTempFile(s"lib$libraryName", ".so")
            tempFile.toFile.deleteOnExit()

            // Copy library to temp file
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

            // Load the library from temp file
            System.load(tempFile.toAbsolutePath.toString)
            logger.info(s"Loaded $libraryName from classpath resource via temp file: ${tempFile.toAbsolutePath}")
            true
          } catch {
            case e: Exception =>
              logger.error(s"Failed to extract and load library from resources", e)
              false
          }
        case None =>
          logger.warn(s"Library resource not found: $resourcePath")
          false
      }
    } catch {
      case e: UnsatisfiedLinkError =>
        logger.error(s"Failed to load native library '$libraryName'", e)
        false
      case e: Exception =>
        logger.error(s"Unexpected error loading native library '$libraryName'", e)
        false
    }
  }

  def isLibraryLoaded: Boolean = libraryLoaded
}