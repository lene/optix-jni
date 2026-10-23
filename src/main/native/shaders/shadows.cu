//==============================================================================
// Shadow ray miss shader - light is visible (no occlusion)
//==============================================================================
extern "C" __global__ void __miss__shadow() {
    // Transparent hits left their optical depth in payloads 0-2 (addShadowDepth; 0 when there
    // were none): the attenuation is what that depth leaves of the light.
    const float tau_r = __uint_as_float(optixGetPayload_0());
    const float tau_g = __uint_as_float(optixGetPayload_1());
    const float tau_b = __uint_as_float(optixGetPayload_2());
    optixSetPayload_0(__float_as_uint(shadowAttenuationFromDepth(tau_r)));
    optixSetPayload_1(__float_as_uint(shadowAttenuationFromDepth(tau_g)));
    optixSetPayload_2(__float_as_uint(shadowAttenuationFromDepth(tau_b)));
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

    const float alpha = effectiveShadowAlpha(material_color.w, material_ior);
    if (alpha >= 1.0f - 1e-4f) return;  // Opaque: accept → closesthit sets full shadow

    accumulateTransparentShadow(material_color, alpha, material_ior, getInstanceCoverage());
    optixIgnoreIntersection();  // Transparent: continue past this object
}

//==============================================================================
// Shadow ray closest hit shader - marks shadow ray as occluded with transparency
//==============================================================================
extern "C" __global__ void __closesthit__shadow() {
    float4 material_color;
    float material_ior;
    getInstanceMaterial(material_color, material_ior);
    const float shadow_alpha = effectiveShadowAlpha(material_color.w, material_ior);
    setShadowPayload(shadow_alpha, material_color);
}
