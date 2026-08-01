package io.github.lene.optix

import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files

import scala.util.Failure
import scala.util.Success
import scala.util.Try
import scala.util.control.Exception.catching

import com.typesafe.scalalogging.LazyLogging

/** Loads a JNI native library that ships as a classpath resource.
  *
  * Lookup order: `java.library.path` first (dev / installed setups), then, on
  * miss, the platform resource `/native/<platform>/lib<name>.so` extracted to a
  * temp file and `System.load`ed.
  *
  * Shared by [[OptiXRenderer]] and by downstream JNI libraries that ship native
  * code alongside optix-jni (e.g. menger-geometry's `libmengergeometry.so`), so
  * the loader lives in one place instead of being forked per consumer.
  *
  * Signatures are deliberately Java-interoperable — no Scala-specific types in
  * the public API — because this is part of the published `io.github.lene.optix`
  * surface (menger's ArchUnit rule enforces this).
  */
object NativeLibrary extends LazyLogging:

  private val DefaultPlatform = "x86_64-linux"

  /** Resource-path platform tag for the current host (currently always
    * `x86_64-linux` on supported platforms). An unsupported host falls back to
    * the default; [[load]] is the real gate — it fails on an unsupported host. */
  def platform(): String = detectPlatform().getOrElse(DefaultPlatform)

  /** Loads `lib<name>.so` (java.library.path, then classpath resource). Returns
    * whether it loaded; failures are logged, not thrown. */
  def load(name: String): Boolean =
    catching(classOf[UnsatisfiedLinkError])
      .withTry(System.loadLibrary(name))
      .recoverWith { case _: UnsatisfiedLinkError => loadFromClasspath(name) }
      .recoverWith { case e: Exception =>
        logger.error(s"Failed to load native library '$name'", e)
        Failure(e)
      }
      .isSuccess

  private def detectPlatform(): Try[String] =
    val os = System.getProperty("os.name").toLowerCase
    val arch = System.getProperty("os.arch").toLowerCase
    if os.contains("linux") && (arch.contains("amd64") || arch.contains("x86_64")) then
      Success(DefaultPlatform)
    else
      Failure(new UnsupportedOperationException(s"Unsupported platform: $os/$arch"))

  private def loadFromClasspath(name: String): Try[Unit] =
    detectPlatform().flatMap: p =>
      val resourcePath = s"/native/$p/lib$name.so"
      Option(getClass.getResourceAsStream(resourcePath)) match
        case None =>
          Failure(new IllegalStateException(s"Library resource not found: $resourcePath"))
        case Some(stream) =>
          extractAndLoad(name, stream)

  private def extractAndLoad(name: String, stream: InputStream): Try[Unit] = Try:
    val tempFile = Files.createTempFile(s"lib$name", ".so")
    tempFile.toFile.deleteOnExit()
    val out = new FileOutputStream(tempFile.toFile)
    try copyStream(stream, out)
    finally { out.close(); stream.close() }
    System.load(tempFile.toAbsolutePath.toString)
    logger.debug(s"Loaded $name from classpath via temp file: ${tempFile.toAbsolutePath}")

  private def copyStream(stream: InputStream, out: FileOutputStream): Unit =
    val buffer = new Array[Byte](8192)
    @scala.annotation.tailrec
    def loop(): Unit =
      stream.read(buffer) match
        case -1 => ()
        case n => out.write(buffer, 0, n); loop()
    loop()
