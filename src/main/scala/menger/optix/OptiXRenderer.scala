package menger.optix

import com.typesafe.scalalogging.LazyLogging
import java.io.{FileOutputStream, InputStream}
import java.nio.file.Files
import scala.util.{Try, Failure}

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
    Try(initialize()).recover {
      case e: UnsatisfiedLinkError =>
        logger.warn("OptiX native library not available", e)
        false
      case e: Exception =>
        logger.error("Error checking OptiX availability", e)
        false
    }.getOrElse(false)
  }
}

object OptiXRenderer extends LazyLogging {
  private val libraryName = "optixjni"
  private val libraryLoaded = loadNativeLibrary().isSuccess

  private def loadFromSystemPath(): Try[Unit] = Try {
    System.loadLibrary(libraryName)
    logger.info(s"Loaded $libraryName from java.library.path")
  }

  private def detectPlatform(): Try[String] = Try {
    val os = System.getProperty("os.name").toLowerCase
    val arch = System.getProperty("os.arch").toLowerCase

    (os, arch) match {
      case (o, a) if o.contains("linux") && (a.contains("amd64") || a.contains("x86_64")) => "x86_64-linux"
      case _ => throw new UnsupportedOperationException(s"Unsupported platform: $os/$arch")
    }
  }

  private def copyStreamToFile(stream: InputStream, out: FileOutputStream): Try[Unit] = Try {
    val buffer = new Array[Byte](8192)

    @scala.annotation.tailrec
    def copyLoop(): Unit = {
      stream.read(buffer) match {
        case -1 => // done
        case bytesRead =>
          out.write(buffer, 0, bytesRead)
          copyLoop()
      }
    }

    copyLoop()
  }

  private def loadFromClasspath(): Try[Unit] = {
    for {
      platform <- detectPlatform()
      resourcePath = s"/native/$platform/lib$libraryName.so"
      _ = logger.debug(s"Attempting to load library from resource: $resourcePath")
      stream <- Option(getClass.getResourceAsStream(resourcePath))
        .toRight(new IllegalStateException(s"Library resource not found: $resourcePath"))
        .toTry
      result <- extractAndLoadLibrary(stream, resourcePath)
    } yield result
  }

  private def extractAndLoadLibrary(stream: InputStream, resourcePath: String): Try[Unit] = Try {
    val tempFile = Files.createTempFile(s"lib$libraryName", ".so")
    tempFile.toFile.deleteOnExit()

    val out = new FileOutputStream(tempFile.toFile)
    try {
      copyStreamToFile(stream, out).get
    } finally {
      out.close()
      stream.close()
    }

    System.load(tempFile.toAbsolutePath.toString)
    logger.info(s"Loaded $libraryName from classpath resource via temp file: ${tempFile.toAbsolutePath}")
  }

  private def loadNativeLibrary(): Try[Unit] = {
    loadFromSystemPath().recoverWith {
      case _: UnsatisfiedLinkError =>
        logger.debug(s"Failed to load $libraryName from java.library.path, trying classpath")
        loadFromClasspath()
    }.recoverWith {
      case e: UnsatisfiedLinkError =>
        logger.error(s"Failed to load native library '$libraryName'", e)
        Failure(e)
      case e: Exception =>
        logger.error(s"Unexpected error loading native library '$libraryName'", e)
        Failure(e)
    }
  }

  def isLibraryLoaded: Boolean = libraryLoaded
}