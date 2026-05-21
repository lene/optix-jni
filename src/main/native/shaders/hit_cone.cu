//==============================================================================
// Cone Ray Intersection Shader
// Implements ray-cone intersection with base cap
//==============================================================================
// This file is included by optix_shaders.cu - do not compile separately

extern "C" __global__ void __intersection__cone() {
    const unsigned int instanceId = optixGetInstanceId();

    if (instanceId >= params.num_instances) return;
    if (!params.instance_materials) return;

    const InstanceMaterial& mat = params.instance_materials[instanceId];
    const int cone_index = mat.texture_index;

    if (cone_index < 0 || cone_index >= static_cast<int>(params.num_cones)) return;
    if (!params.cone_data) return;

    const ConeData* cone = &params.cone_data[cone_index];

    const float3 ray_orig = optixGetWorldRayOrigin();
    const float3 ray_dir  = optixGetWorldRayDirection();

    const float3 apex = make_float3(cone->apex[0], cone->apex[1], cone->apex[2]);
    const float3 base = make_float3(cone->base[0], cone->base[1], cone->base[2]);
    const float  radius = cone->radius;

    // Axis from apex to base
    const float3 axis_vec = make_float3(base.x - apex.x, base.y - apex.y, base.z - apex.z);
    const float height = sqrtf(axis_vec.x*axis_vec.x + axis_vec.y*axis_vec.y + axis_vec.z*axis_vec.z);
    if (height < 1e-8f) return;

    const float3 axis = make_float3(axis_vec.x/height, axis_vec.y/height, axis_vec.z/height);
    const float  cos2 = (height * height) / (height * height + radius * radius);

    // Ray relative to apex
    const float3 co = make_float3(ray_orig.x - apex.x, ray_orig.y - apex.y, ray_orig.z - apex.z);

    const float rd_dot_a  = ray_dir.x*axis.x + ray_dir.y*axis.y + ray_dir.z*axis.z;
    const float co_dot_a  = co.x*axis.x + co.y*axis.y + co.z*axis.z;

    const float a = rd_dot_a*rd_dot_a - cos2*(ray_dir.x*ray_dir.x + ray_dir.y*ray_dir.y + ray_dir.z*ray_dir.z);
    const float b = 2.0f*(rd_dot_a*co_dot_a - cos2*(ray_dir.x*co.x + ray_dir.y*co.y + ray_dir.z*co.z));
    const float c = co_dot_a*co_dot_a - cos2*(co.x*co.x + co.y*co.y + co.z*co.z);

    const float disc = b*b - 4.0f*a*c;
    if (disc < 0.0f || fabsf(a) < 1e-10f) return;

    const float sqrt_disc = sqrtf(disc);
    float t_hit = -1.0f;
    float3 normal = make_float3(0.0f, 1.0f, 0.0f);

    for (int i = 0; i < 2; i++) {
        const float t = (i == 0) ? (-b - sqrt_disc)/(2.0f*a) : (-b + sqrt_disc)/(2.0f*a);
        if (t <= optixGetRayTmin() || t >= optixGetRayTmax()) continue;

        const float3 hp = make_float3(ray_orig.x + t*ray_dir.x, ray_orig.y + t*ray_dir.y, ray_orig.z + t*ray_dir.z);
        const float3 hv = make_float3(hp.x - apex.x, hp.y - apex.y, hp.z - apex.z);
        const float  s  = hv.x*axis.x + hv.y*axis.y + hv.z*axis.z;

        // Must be between apex (s=0) and base (s=height) on the forward half-cone
        if (s < 0.0f || s > height) continue;

        if (t_hit < 0.0f || t < t_hit) {
            t_hit = t;
            // Cone surface normal: n = normalize(hv - s*axis - cos2*(-axis)*len(hv))
            // Simpler: gradient of (dot(hv,axis))^2 - cos2*dot(hv,hv) = 0
            const float3 n_raw = make_float3(
                2.0f*(hv.x*axis.x*s - cos2*hv.x),  // approximation; correct below
                2.0f*(hv.y*axis.y*s - cos2*hv.y),
                2.0f*(hv.z*axis.z*s - cos2*hv.z)
            );
            // Proper cone normal: n = hv/|hv| * cos(theta) - axis * sin(theta)
            // sin^2 = 1-cos2, cos2 = cos^2(half-angle)
            const float hv_len = sqrtf(hv.x*hv.x + hv.y*hv.y + hv.z*hv.z);
            if (hv_len > 1e-8f) {
                const float3 hv_n = make_float3(hv.x/hv_len, hv.y/hv_len, hv.z/hv_len);
                const float cos_a = sqrtf(cos2);
                const float sin_a = sqrtf(1.0f - cos2);
                normal = make_float3(
                    hv_n.x*cos_a - axis.x*sin_a,
                    hv_n.y*cos_a - axis.y*sin_a,
                    hv_n.z*cos_a - axis.z*sin_a
                );
                const float n_len = sqrtf(normal.x*normal.x + normal.y*normal.y + normal.z*normal.z);
                if (n_len > 1e-8f) {
                    normal = make_float3(normal.x/n_len, normal.y/n_len, normal.z/n_len);
                }
            }
        }
    }

    if (t_hit > 0.0f) {
        optixReportIntersection(
            t_hit, 0,
            __float_as_uint(normal.x),
            __float_as_uint(normal.y),
            __float_as_uint(normal.z)
        );
    }
}

extern "C" __global__ void __closesthit__cone() {
    const float t = optixGetRayTmax();
    const float3 ray_origin    = optixGetWorldRayOrigin();
    const float3 ray_direction = optixGetWorldRayDirection();
    const float3 hit_point = make_float3(
        ray_origin.x + ray_direction.x * t,
        ray_origin.y + ray_direction.y * t,
        ray_origin.z + ray_direction.z * t
    );

    float3 normal = make_float3(
        __uint_as_float(optixGetAttribute_0()),
        __uint_as_float(optixGetAttribute_1()),
        __uint_as_float(optixGetAttribute_2())
    );

    const unsigned int depth = optixGetPayload_3();

    if (params.stats) {
        atomicMax(&params.stats->max_depth_reached, depth + 1);
        atomicMin(&params.stats->min_depth_reached, depth + 1);
    }

    float4 material_color;
    float material_ior, roughness, metallic, specular, emission, film_thickness;
    getInstanceMaterialPBR(material_color, material_ior, roughness, metallic, specular, emission, film_thickness);

    int proc_type; float proc_scale;
    getInstanceProceduralParams(proc_type, proc_scale);
    if (proc_type != 0)
        material_color = applyProceduralTexture(material_color, hit_point, normal, proc_type, proc_scale);

    // Apply image texture + PBR maps (Task 21.6)
    {
        const unsigned int instanceId = optixGetInstanceId();
        if (params.instance_materials && params.cone_data) {
            const int cone_idx = params.instance_materials[instanceId].texture_index;
            if (cone_idx >= 0 && cone_idx < static_cast<int>(params.num_cones)) {
                const ConeData& cone = params.cone_data[cone_idx];
                const float3 apex_pt = make_float3(cone.apex[0], cone.apex[1], cone.apex[2]);
                const float3 base_pt = make_float3(cone.base[0], cone.base[1], cone.base[2]);
                const float3 axis_vec = make_float3(apex_pt.x - base_pt.x,
                                                     apex_pt.y - base_pt.y,
                                                     apex_pt.z - base_pt.z);
                const float cone_height = length(axis_vec);
                const float3 axis_dir = (cone_height > 1e-8f)
                    ? make_float3(axis_vec.x / cone_height, axis_vec.y / cone_height, axis_vec.z / cone_height)
                    : make_float3(0.0f, 1.0f, 0.0f);

                // v: normalized position along axis (0=base, 1=apex)
                const float3 hit_to_base = make_float3(hit_point.x - base_pt.x,
                                                        hit_point.y - base_pt.y,
                                                        hit_point.z - base_pt.z);
                const float v_coord = (cone_height > 1e-8f)
                    ? fminf(fmaxf(dot(hit_to_base, axis_dir) / cone_height, 0.0f), 1.0f)
                    : 0.0f;

                // u: angle around axis
                float3 tangent;
                if (fabsf(axis_dir.x) < 0.9f)
                    tangent = normalize(cross(axis_dir, make_float3(1.0f, 0.0f, 0.0f)));
                else
                    tangent = normalize(cross(axis_dir, make_float3(0.0f, 1.0f, 0.0f)));
                const float3 bitangent = cross(axis_dir, tangent);
                const float3 radial = make_float3(
                    hit_to_base.x - dot(hit_to_base, axis_dir) * axis_dir.x,
                    hit_to_base.y - dot(hit_to_base, axis_dir) * axis_dir.y,
                    hit_to_base.z - dot(hit_to_base, axis_dir) * axis_dir.z);
                const float u_coord = (atan2f(dot(radial, bitangent), dot(radial, tangent)) + M_PIf)
                                      * (0.5f * M_1_PIf);

                const float2 cone_uv = make_float2(u_coord, v_coord);

                const int img_tex_idx = getInstanceImageTextureIndex();
                if (img_tex_idx >= 0)
                    material_color = sampleTextureByIndex(img_tex_idx, cone_uv);

                const int normal_idx = getInstanceNormalTextureIndex();
                if (normal_idx >= 0)
                    normal = applyNormalMap(normal, cone_uv, normal_idx);

                roughness = applyRoughnessMap(roughness, cone_uv, getInstanceRoughnessTextureIndex());
            }
        }
    }

    if (depth == 0 && metallic > 0.0f) {
        handleMetallicOpaque(hit_point, ray_direction, normal,
                             material_color, metallic, depth, emission);
        return;
    }

    const float3 final_lighting = calculateLighting(hit_point, normal, false, true);

    const float final_r = material_color.x * final_lighting.x * RayTracingConstants::COLOR_SCALE_FACTOR;
    const float final_g = material_color.y * final_lighting.y * RayTracingConstants::COLOR_SCALE_FACTOR;
    const float final_b = material_color.z * final_lighting.z * RayTracingConstants::COLOR_SCALE_FACTOR;

    unsigned int cone_r = static_cast<unsigned int>(fminf(final_r + emission * material_color.x * RayTracingConstants::COLOR_BYTE_MAX, RayTracingConstants::COLOR_BYTE_MAX));
    unsigned int cone_g = static_cast<unsigned int>(fminf(final_g + emission * material_color.y * RayTracingConstants::COLOR_BYTE_MAX, RayTracingConstants::COLOR_BYTE_MAX));
    unsigned int cone_b = static_cast<unsigned int>(fminf(final_b + emission * material_color.z * RayTracingConstants::COLOR_BYTE_MAX, RayTracingConstants::COLOR_BYTE_MAX));
    applyFogInPlace(cone_r, cone_g, cone_b, optixGetRayTmax());
    optixSetPayload_0(cone_r);
    optixSetPayload_1(cone_g);
    optixSetPayload_2(cone_b);
}

extern "C" __global__ void __anyhit__cone_shadow() {
    if (!params.transparent_shadows_enabled) return;

    float4 material_color;
    float material_ior;
    getInstanceMaterial(material_color, material_ior);

    const float alpha = material_color.w;
    if (alpha >= 1.0f - 1e-4f) return;

    accumulateShadowAttenuation(alpha, material_color);
    optixIgnoreIntersection();
}

extern "C" __global__ void __closesthit__cone_shadow() {
    float4 material_color;
    float material_ior;
    getInstanceMaterial(material_color, material_ior);
    setShadowPayload(material_color.w, material_color);
}
