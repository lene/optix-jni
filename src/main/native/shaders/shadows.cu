//==============================================================================
// Shadow ray miss shader - light is visible (no occlusion)
//==============================================================================
extern "C" __global__ void __miss__shadow() {
    // Shadow ray missed all geometry - no occlusion, return 0.0 attenuation
    optixSetPayload_0(__float_as_uint(0.0f));
    optixSetPayload_1(__float_as_uint(0.0f));
    optixSetPayload_2(__float_as_uint(0.0f));
}

//==============================================================================
// Shadow ray closest hit shader - marks shadow ray as occluded with transparency
//==============================================================================
extern "C" __global__ void __closesthit__shadow() {
    // Shadow ray hit an object - return alpha as shadow attenuation
    // alpha=0.0 (transparent) → attenuation=0.0 (no shadow)
    // alpha=1.0 (opaque) → attenuation=1.0 (full shadow)

    // Get material properties (from IAS instance or global params)
    float4 material_color;
    float material_ior;
    getInstanceMaterial(material_color, material_ior);
    const float alpha = material_color.w;

    if (params.transparent_shadows_enabled) {
        // Colored attenuation: how much of each channel is BLOCKED
        // A red sphere (rgb=1,0,0) with alpha=0.8 blocks 0% red, 80% green, 80% blue
        optixSetPayload_0(__float_as_uint(alpha * (1.0f - material_color.x)));
        optixSetPayload_1(__float_as_uint(alpha * (1.0f - material_color.y)));
        optixSetPayload_2(__float_as_uint(alpha * (1.0f - material_color.z)));
    } else {
        // Scalar mode: uniform attenuation across all channels (backward-compatible)
        optixSetPayload_0(__float_as_uint(alpha));
        optixSetPayload_1(__float_as_uint(alpha));
        optixSetPayload_2(__float_as_uint(alpha));
    }
}
