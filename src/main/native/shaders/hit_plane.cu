//==============================================================================
// Plane Ray Intersection Shader
// Implements ray-plane intersection as a first-class geometry primitive
//==============================================================================
// This file is included by optix_shaders.cu - do not compile separately

//==============================================================================
// Plane Intersection Program
//==============================================================================

extern "C" __global__ void __intersection__plane() {
    const unsigned int instanceId = optixGetInstanceId();

    if (instanceId >= params.num_instances) return;
    if (!params.instance_materials) return;

    const InstanceMaterial& mat = params.instance_materials[instanceId];
    const int plane_index = mat.texture_index;

    if (plane_index < 0 || plane_index >= static_cast<int>(params.num_planes)) return;
    if (!params.plane_data) return;

    const PlaneData* plane = &params.plane_data[plane_index];

    const float3 ray_orig = optixGetWorldRayOrigin();
    const float3 ray_dir  = optixGetWorldRayDirection();

    const float3 normal = make_float3(plane->normal[0], plane->normal[1], plane->normal[2]);
    const float  distance = plane->distance;

    const float denom = ray_dir.x * normal.x + ray_dir.y * normal.y + ray_dir.z * normal.z;

    if (fabsf(denom) < 1e-8f) return;

    const float numerator = distance - (ray_orig.x * normal.x + ray_orig.y * normal.y + ray_orig.z * normal.z);
    const float t = numerator / denom;

    if (t > optixGetRayTmin() && t < optixGetRayTmax()) {
        optixReportIntersection(
            t,
            0,  // hit_kind = 0 (no entry/exit, plane has zero thickness)
            __float_as_uint(normal.x),
            __float_as_uint(normal.y),
            __float_as_uint(normal.z)
        );
    }
}

//==============================================================================
// Plane Closest Hit Program
//==============================================================================

extern "C" __global__ void __closesthit__plane() {
    const float t = optixGetRayTmax();
    const float3 ray_origin    = optixGetWorldRayOrigin();
    const float3 ray_direction = optixGetWorldRayDirection();
    const float3 hit_point = ray_origin + ray_direction * t;

    const float3 geometric_normal = make_float3(
        __uint_as_float(optixGetAttribute_0()),
        __uint_as_float(optixGetAttribute_1()),
        __uint_as_float(optixGetAttribute_2())
    );

    // Orient normal to face the incoming ray
    const bool front_face = dot(geometric_normal, ray_direction) < 0.0f;
    const float3 normal = front_face
        ? geometric_normal
        : make_float3(-geometric_normal.x, -geometric_normal.y, -geometric_normal.z);

    float4 material_color;
    float material_ior, roughness, metallic, specular, emission, film_thickness;
    getInstanceMaterialPBR(material_color, material_ior, roughness, metallic,
                           specular, emission, film_thickness);
    const float material_alpha = material_color.w;

    // Fully transparent: pass through
    if (material_alpha < ALPHA_FULLY_TRANSPARENT_THRESHOLD) {
        handleFullyTransparent(hit_point, ray_direction, optixGetPayload_3());
        return;
    }

    // Opaque: diffuse or metallic (no refraction - plane has no volume)
    if (metallic > 0.0f) {
        handleMetallicOpaque(hit_point, ray_direction, normal,
                             material_color, metallic, optixGetPayload_3(), emission);
        return;
    }
    handleFullyOpaque(hit_point, normal, material_color, emission);
}

//==============================================================================
// Plane Shadow Programs
//==============================================================================

extern "C" __global__ void __anyhit__plane_shadow() {
    if (!params.transparent_shadows_enabled) return;

    float4 material_color;
    float material_ior;
    getInstanceMaterial(material_color, material_ior);

    const float alpha = material_color.w;
    if (alpha >= 1.0f - 1e-4f) return;

    accumulateShadowAttenuation(alpha, material_color);
    optixIgnoreIntersection();
}

extern "C" __global__ void __closesthit__plane_shadow() {
    float4 material_color;
    float material_ior;
    getInstanceMaterial(material_color, material_ior);
    setShadowPayload(material_color.w, material_color);
}
