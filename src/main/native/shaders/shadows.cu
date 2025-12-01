//==============================================================================
// Shadow ray miss shader - light is visible (no occlusion)
//==============================================================================
extern "C" __global__ void __miss__shadow() {
    // Shadow ray missed all geometry - no occlusion, return 0.0 attenuation
    optixSetPayload_0(__float_as_uint(0.0f));
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

    // Pack float as bits into unsigned int payload
    optixSetPayload_0(__float_as_uint(alpha));
}
