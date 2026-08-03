package io.github.lene.optix

/** Canonical material float layout crossing the JNI boundary (Sprint 35 Task 3.1 / F2).
  *
  * Every `add*Instance` / `setInstanceMaterial` native entry receives the material as a single
  * `Array[Float]` of length [[FloatCount]], in exactly the order produced here, unpacked once on
  * the native side (see `MaterialPayload.h` / `readMaterialPayload`). Routing one array instead
  * of ~12 positional floats through eight signatures removes the positional-mismatch hazard and
  * gives one home for the Cauchy-coefficient derivation.
  */
object MaterialPayload:

  /** Number of floats packed per material. Pinned by `MaterialPayloadSuite` and the native
    * `MaterialPayloadTest`; must equal `MATERIAL_PAYLOAD_FLOAT_COUNT` in `MaterialPayload.h`. */
  val FloatCount: Int = 12

  /** The canonical field order. This is the single definition of the layout; every packer below
    * routes through it so the order cannot drift between call sites. */
  def of(
      r: Float, g: Float, b: Float, a: Float, ior: Float,
      roughness: Float, metallic: Float, specular: Float, emission: Float,
      filmThickness: Float, cauchyA: Float, cauchyB: Float): Array[Float] =
    Array(r, g, b, a, ior, roughness, metallic, specular, emission, filmThickness, cauchyA, cauchyB)

  /** Packs a [[Material]], deriving Cauchy dispersion coefficients from ior + Abbe number. */
  def pack(material: Material): Array[Float] =
    val (cauchyA, cauchyB) = Material.cauchyCoefficients(material.ior, material.dispersion)
    of(
      material.color.r, material.color.g, material.color.b, material.color.a,
      material.ior, material.roughness, material.metallic, material.specular, material.emission,
      material.filmThickness, cauchyA, cauchyB)
