package io.github.lene.optix

import java.nio.file.Files
import java.nio.file.Paths
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** The Scala mirror of a C++ constant must not drift from it (optix-jni#132). */
class GlassShadowConstantsSuite extends AnyFlatSpec with Matchers:

  private val header = Paths.get("src/main/native/include/OptiXData.h")
  private val retention = """GlassShadowConstants\s*\{[^}]*?RETENTION\s*=\s*([0-9.]+)f""".r

  "ThresholdConstants.GLASS_SHADOW_RETENTION" should "equal GlassShadowConstants::RETENTION in OptiXData.h" in:
    val source = new String(Files.readAllBytes(header), "UTF-8")
    val native = retention.findFirstMatchIn(source).map(_.group(1).toDouble)
    native shouldBe Some(ThresholdConstants.GLASS_SHADOW_RETENTION)
