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
    const int plane_index = mat.geometry_data_index;

    if (plane_index < 0 || plane_index >= static_cast<int>(params.num_plane_data)) return;
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
    float3 normal = front_face
        ? geometric_normal
        : make_float3(-geometric_normal.x, -geometric_normal.y, -geometric_normal.z);

    float4 material_color;
    float material_ior, roughness, metallic, specular, emission, film_thickness, cauchy_a, cauchy_b;
    getInstanceMaterialPBR(material_color, material_ior, roughness, metallic,
                           specular, emission, film_thickness, cauchy_a, cauchy_b);

    // Apply checker pattern from plane geometry data
    const unsigned int instanceId = optixGetInstanceId();
    if (params.instance_materials && params.plane_data) {
        const int plane_index = params.instance_materials[instanceId].geometry_data_index;
        if (plane_index >= 0 && plane_index < static_cast<int>(params.num_plane_data)) {
            const PlaneData& pd = params.plane_data[plane_index];
            float3 base_color;
            if (pd.solid_color) {
                base_color = make_float3(pd.color1[0], pd.color1[1], pd.color1[2]);
            } else {
                const float3 abs_n = make_float3(
                    fabsf(geometric_normal.x),
                    fabsf(geometric_normal.y),
                    fabsf(geometric_normal.z));
                float u, v;
                if (abs_n.x >= abs_n.y && abs_n.x >= abs_n.z) {
                    u = hit_point.y; v = hit_point.z;
                } else if (abs_n.y >= abs_n.z) {
                    u = hit_point.x; v = hit_point.z;
                } else {
                    u = hit_point.x; v = hit_point.y;
                }
                const float cs = pd.checker_size > 0.0f ? pd.checker_size : 1.0f;
                const int cu = static_cast<int>(floorf(u / cs));
                const int cv = static_cast<int>(floorf(v / cs));
                const bool is_light = ((cu + cv) & 1) == 0;
                base_color = is_light
                    ? make_float3(pd.color1[0], pd.color1[1], pd.color1[2])
                    : make_float3(pd.color2[0], pd.color2[1], pd.color2[2]);
            }
            material_color = make_float4(base_color.x, base_color.y, base_color.z, material_color.w);
        }
    }

    int proc_type; float proc_scale;
    getInstanceProceduralParams(proc_type, proc_scale);
    if (proc_type != 0)
        material_color = applyProceduralTexture(material_color, hit_point, normal, proc_type, proc_scale);

    // Apply image texture + PBR maps (Task 21.6)
    // Planar UV based on dominant normal axis (matches checker coordinate logic)
    {
        const float3 abs_n = make_float3(fabsf(geometric_normal.x),
                                          fabsf(geometric_normal.y),
                                          fabsf(geometric_normal.z));
        float pu, pv;
        if (abs_n.x >= abs_n.y && abs_n.x >= abs_n.z) {
            pu = hit_point.y; pv = hit_point.z;
        } else if (abs_n.y >= abs_n.z) {
            pu = hit_point.x; pv = hit_point.z;
        } else {
            pu = hit_point.x; pv = hit_point.y;
        }
        // Wrap to [0,1] for repeating texture
        pu = pu - floorf(pu);
        pv = pv - floorf(pv);
        const float2 plane_uv = make_float2(pu, pv);

        const int img_tex_idx = getInstanceImageTextureIndex();
        if (img_tex_idx >= 0)
            material_color = sampleTextureByIndex(img_tex_idx, plane_uv);

        const int normal_idx = getInstanceNormalTextureIndex();
        if (normal_idx >= 0)
            normal = applyNormalMap(geometric_normal, plane_uv, normal_idx);

        roughness = applyRoughnessMap(roughness, plane_uv, getInstanceRoughnessTextureIndex());
    }

    writeDenoiseGuides(material_color, normal);

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
    // Use geometric_normal (pre-flip) with double_sided=true to match legacy miss-shader lighting:
    // planes are lit from both sides using fabsf(NdotL) regardless of view direction.
    handleFullyOpaque(hit_point, geometric_normal, material_color, emission, true);
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
