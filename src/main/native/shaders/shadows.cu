//==============================================================================
// Shadow ray miss shader - light is visible (no occlusion)
//==============================================================================
extern "C" __global__ void __miss__shadow() {
    // Transparent hits left their optical depth in payloads 0-2 (addShadowDepth; 0 when there
    // were none): the attenuation is what that depth leaves of the light.
    const float tau_r = __uint_as_float(optixGetPayload_0());
    const float tau_g = __uint_as_float(optixGetPayload_1());
    const float tau_b = __uint_as_float(optixGetPayload_2());
    const float bent = bentLightDepth();
    optixSetPayload_0(__float_as_uint(shadowAttenuationFromDepth(tau_r + bent)));
    optixSetPayload_1(__float_as_uint(shadowAttenuationFromDepth(tau_g + bent)));
    optixSetPayload_2(__float_as_uint(shadowAttenuationFromDepth(tau_b + bent)));
}

//==============================================================================
// Shadow ray anyhit shader - accumulates attenuation through transparent objects
//==============================================================================
extern "C" __global__ void __anyhit__shadow() {
    float4 material_color;
    float material_ior;
    getInstanceMaterial(material_color, material_ior);

    // Without transparent shadows, let closesthit handle it (Phase 1 behavior), except for glass
    // that bends the light of the ray's light: its shadow is the physical one (optix-jni#132).
    const bool bends_light = glassBendsLight(material_color.w, material_ior);
    if (!params.transparent_shadows_enabled && !bends_light) return;

    // Sphere intersection reports hit_kind 0=ENTER, 1=EXIT.
    // Ignore EXIT hits to count each sphere once.
    if (optixGetHitKind() != 0) {
        optixIgnoreIntersection();
        return;
    }

    const float alpha = effectiveShadowAlpha(material_color.w, material_ior);
    if (alpha >= 1.0f - RayTracingConstants::SHADOW_OPAQUE_ALPHA_EPSILON) return;  // Opaque: accept → closesthit sets full shadow

    accumulateTransparentShadow(material_color, alpha, material_ior, getInstanceCoverage());
    if (bends_light) markGlassCrossed();
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
