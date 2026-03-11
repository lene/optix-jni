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
    float4 material_color;
    float material_ior;
    getInstanceMaterial(material_color, material_ior);
    setShadowPayload(material_color.w, material_color);
}
