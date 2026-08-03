#ifndef OPTIX_MATERIAL_PAYLOAD_H
#define OPTIX_MATERIAL_PAYLOAD_H

// Canonical material payload crossing the JVM<->native boundary (Sprint 35 Task 3.1 / F2).
//
// Before this, the material crossed JNI as ~12 positional `jfloat` arguments repeated across
// eight `add*Instance` / `setInstanceMaterial` entries — the exact positional-mismatch bug
// class the generated JNI headers were meant to guard. The JVM now packs exactly these fields,
// in this order, into a `float[]` of length MATERIAL_PAYLOAD_FLOAT_COUNT; readMaterialPayload()
// (JNIBindings.cpp) unpacks it once.
//
// Keep this layout in lockstep with io.github.lene.optix.MaterialPayload on the Scala side.
// The field count is pinned on both sides — MaterialPayloadTest (native, sizeof/field-count)
// and MaterialPayloadSuite (Scala) — and readMaterialPayload rejects a wrong-length array at
// runtime, so a resize/reorder on one side fails a test or the first instance call.
struct MaterialPayload {
    float r, g, b, a;      // RGBA color (alpha: 0=transparent, 1=opaque)
    float ior;             // Index of refraction
    float roughness;       // 0=mirror .. 1=diffuse
    float metallic;        // 0=dielectric .. 1=metal
    float specular;        // Specular intensity
    float emission;        // Emission intensity
    float film_thickness;  // Thin-film thickness in nm (0 = none)
    float cauchy_a;        // Cauchy A coefficient for n(lambda) = A + B/lambda^2
    float cauchy_b;        // Cauchy B coefficient (0 = no dispersion)
};

// Number of floats the JVM packs per material. Must equal sizeof(MaterialPayload)/sizeof(float).
constexpr int MATERIAL_PAYLOAD_FLOAT_COUNT = 12;

#endif  // OPTIX_MATERIAL_PAYLOAD_H
