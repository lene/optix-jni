package io.github.lene.optix

import java.nio.file.Files
import java.nio.file.Paths

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** optix-jni#55: loading the library wrote `target/native/x86_64-linux/bin/optix_shaders.ptx`
  * into whatever directory the JVM was started from. The PTX now goes into a temp directory and
  * native code is told where it is. */
class PtxExtractionSuite extends AnyFlatSpec with Matchers:

  "OptiXRenderer" should "extract the bundled PTX into a temp directory, not the working directory" in:
    assume(OptiXRenderer.isLibraryLoaded, "OptiX native library not available")
    val path = Paths.get(OptiXRenderer.extractedPtxPath.getOrElse(fail("no PTX was extracted")))
    path.isAbsolute shouldBe true
    path.startsWith(Paths.get(System.getProperty("java.io.tmpdir"))) shouldBe true
    Files.size(path) should be > 0L

  it should "initialize a renderer from the extracted PTX" in:
    assume(OptiXRenderer.isLibraryLoaded, "OptiX native library not available")
    val renderer = new OptiXRenderer()
    try renderer.initialize() shouldBe true
    finally renderer.dispose()
