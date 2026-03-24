//==============================================================================
// Shadow ray miss shader - light is visible (no occlusion)
//==============================================================================
extern "C" __global__ void __miss__shadow() {
    // Payloads pre-initialized to 0.0f by traceShadowRay().
    // Accumulated transparent attenuation (if any) is preserved here.
}

//==============================================================================
// Shadow ray anyhit shader - accumulates attenuation through transparent objects
//==============================================================================
extern "C" __global__ void __anyhit__shadow() {
    // Without transparent shadows, let closesthit handle it (Phase 1 behavior).
    if (!params.transparent_shadows_enabled) return;

    // Sphere intersection reports hit_kind 0=ENTER, 1=EXIT.
    // Ignore EXIT hits to count each sphere once.
    if (optixGetHitKind() != 0) {
        optixIgnoreIntersection();
        return;
    }

    float4 material_color;
    float material_ior;
    getInstanceMaterial(material_color, material_ior);

    const float alpha = material_color.w;
    if (alpha >= 1.0f - 1e-4f) return;  // Opaque: accept → closesthit sets full shadow

    accumulateShadowAttenuation(alpha, material_color);
    optixIgnoreIntersection();  // Transparent: continue past this object
}

//==============================================================================
// Shadow ray closest hit shader - marks shadow ray as occluded with transparency
//==============================================================================
extern "C" __global__ void __closesthit__shadow() {
    float4 material_color;
    float material_ior;
    getInstanceMaterial(material_color, material_ior);
    setShadowPayload(material_color.w, material_color);
}
