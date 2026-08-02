package io.github.lene.optix

import menger.common.Color
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Scala half of the F2 fitness function (Sprint 35 Task 3.1). Pins the packed material layout
  * that crosses JNI so it cannot diverge from the C++ `MaterialPayload` (native side,
  * `MaterialPayloadTest`) — `FloatCount` and the field order here must match
  * `MATERIAL_PAYLOAD_FLOAT_COUNT` / the struct in `MaterialPayload.h`. */
class MaterialPayloadSuite extends AnyFlatSpec with Matchers:

  "MaterialPayload.of" should "pack exactly FloatCount floats in canonical order" in:
    val packed = MaterialPayload.of(
      r = 0.1f, g = 0.2f, b = 0.3f, a = 0.4f, ior = 1.5f,
      roughness = 0.6f, metallic = 0.7f, specular = 0.8f, emission = 0.9f,
      filmThickness = 500.0f, cauchyA = 1.6f, cauchyB = 0.004f)
    packed.length shouldBe MaterialPayload.FloatCount
    packed shouldBe Array(
      0.1f, 0.2f, 0.3f, 0.4f, 1.5f, 0.6f, 0.7f, 0.8f, 0.9f, 500.0f, 1.6f, 0.004f)

  it should "keep FloatCount at 12 (locked to the native MATERIAL_PAYLOAD_FLOAT_COUNT)" in:
    MaterialPayload.FloatCount shouldBe 12

  "MaterialPayload.pack" should "place color, PBR and film fields at their canonical offsets" in:
    val material = Material(
      color = Color(0.1f, 0.2f, 0.3f, 0.4f), ior = 1.5f,
      roughness = 0.6f, metallic = 0.7f, specular = 0.8f, emission = 0.9f,
      filmThickness = 500.0f)
    val packed = MaterialPayload.pack(material)
    packed.length shouldBe MaterialPayload.FloatCount
    packed.take(10) shouldBe Array(
      0.1f, 0.2f, 0.3f, 0.4f, 1.5f, 0.6f, 0.7f, 0.8f, 0.9f, 500.0f)

  it should "derive Cauchy coefficients into the last two slots" in:
    val nonDispersive = MaterialPayload.pack(Material(Color(1f, 1f, 1f, 1f), ior = 1.5f))
    nonDispersive(10) shouldBe 1.5f  // cauchyA == ior when dispersion is 0
    nonDispersive(11) shouldBe 0.0f  // cauchyB == 0 disables dispersion

    val dispersive = MaterialPayload.pack(
      Material(Color(1f, 1f, 1f, 1f), ior = 1.5f, dispersion = 59f))
    dispersive(11) should be > 0.0f  // non-zero Cauchy B once dispersion is set
