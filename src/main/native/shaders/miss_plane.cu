//==============================================================================
// Miss Shader — Legacy Plane Intersection + Background Fallback
//==============================================================================
// Planes are available as first-class geometry (hit_plane.cu) since Sprint 19.
// The legacy miss-shader plane path remains for backward compatibility with
// the --plane CLI flag and existing tests. It will be removed in a future sprint.
//==============================================================================
// This file is included by optix_shaders.cu - do not compile separately

//==============================================================================
// Plane helper functions (used by caustics and legacy miss shader)
//==============================================================================

__device__ void getRayPlaneComponents(
    const float3& ray_origin,
    const float3& ray_direction,
    int plane_axis,
    float& ray_orig_comp,
    float& ray_dir_comp
) {
    if (plane_axis == 0) {
        ray_orig_comp = ray_origin.x;
        ray_dir_comp = ray_direction.x;
    } else if (plane_axis == 1) {
        ray_orig_comp = ray_origin.y;
        ray_dir_comp = ray_direction.y;
    } else {
        ray_orig_comp = ray_origin.z;
        ray_dir_comp = ray_direction.z;
    }
}

__device__ void getCheckerboardCoordinates(
    const float3& hit_point,
    int plane_axis,
    float& checker_u,
    float& checker_v
) {
    if (plane_axis == 0) {
        checker_u = hit_point.y;
        checker_v = hit_point.z;
    } else if (plane_axis == 1) {
        checker_u = hit_point.x;
        checker_v = hit_point.z;
    } else {
        checker_u = hit_point.x;
        checker_v = hit_point.y;
    }
}

__device__ float3 getPlaneColor(const PlaneParams& plane, float checker_u, float checker_v) {
    if (plane.solid_color) {
        return make_float3(plane.color1[0], plane.color1[1], plane.color1[2]);
    }

    const float checker_size = PLANE_CHECKER_SIZE;
    const int check_u = static_cast<int>(floorf(checker_u / checker_size));
    const int check_v = static_cast<int>(floorf(checker_v / checker_size));
    const bool is_light = ((check_u + check_v) & 1) == 0;

    if (is_light) {
        return make_float3(plane.color1[0], plane.color1[1], plane.color1[2]);
    } else {
        return make_float3(plane.color2[0], plane.color2[1], plane.color2[2]);
    }
}

__device__ float3 getPlaneNormal(const PlaneParams& plane) {
    float3 n;
    if (plane.axis == 0) {
        n = make_float3(1.0f, 0.0f, 0.0f);
    } else if (plane.axis == 1) {
        n = make_float3(0.0f, 1.0f, 0.0f);
    } else {
        n = make_float3(0.0f, 0.0f, 1.0f);
    }
    return plane.positive ? n : make_float3(-n.x, -n.y, -n.z);
}

__device__ void getBackgroundColor(
    unsigned int& r,
    unsigned int& g,
    unsigned int& b
) {
    r = static_cast<unsigned int>(params.bg_r * COLOR_SCALE_FACTOR);
    g = static_cast<unsigned int>(params.bg_g * COLOR_SCALE_FACTOR);
    b = static_cast<unsigned int>(params.bg_b * COLOR_SCALE_FACTOR);
}

__device__ float3 applyToneMapping(float3 c) {
    const float e = params.tonemap_exposure;
    if (params.tonemap_operator == 1) {
        // Reinhard: c*e / (1 + c*e)
        c.x = c.x * e / (1.0f + c.x * e);
        c.y = c.y * e / (1.0f + c.y * e);
        c.z = c.z * e / (1.0f + c.z * e);
    } else if (params.tonemap_operator == 2) {
        // ACES filmic approximation (Narkowicz 2015)
        c.x *= e; c.y *= e; c.z *= e;
        const float a = 2.51f, b2 = 0.03f, cc = 2.43f, d = 0.59f, e2 = 0.14f;
        c.x = fminf((c.x*(a*c.x+b2))/(c.x*(cc*c.x+d)+e2), 1.0f);
        c.y = fminf((c.y*(a*c.y+b2))/(c.y*(cc*c.y+d)+e2), 1.0f);
        c.z = fminf((c.z*(a*c.z+b2))/(c.z*(cc*c.z+d)+e2), 1.0f);
    } else {
        c.x = fminf(c.x, 1.0f);
        c.y = fminf(c.y, 1.0f);
        c.z = fminf(c.z, 1.0f);
    }
    return c;
}

__device__ void sampleEnvMap(unsigned int& r, unsigned int& g, unsigned int& b) {
    float3 dir = normalize(optixGetWorldRayDirection());
    float u = 0.5f + atan2f(dir.z, dir.x) * (0.5f * M_1_PIf);
    float v = 0.5f - asinf(fmaxf(-1.f, fminf(1.f, dir.y))) * M_1_PIf;
    float4 c = tex2D<float4>(params.env_map_texture, u, v);
    float3 mapped = applyToneMapping(make_float3(c.x, c.y, c.z));
    r = static_cast<unsigned int>(mapped.x * COLOR_SCALE_FACTOR);
    g = static_cast<unsigned int>(mapped.y * COLOR_SCALE_FACTOR);
    b = static_cast<unsigned int>(mapped.z * COLOR_SCALE_FACTOR);
}

//==============================================================================
// Miss Shader — Planes in miss path (legacy) or background fallback
//==============================================================================
extern "C" __global__ void __miss__ms() {
    const float3 ray_origin = optixGetWorldRayOrigin();
    const float3 ray_direction = optixGetWorldRayDirection();

    unsigned int r, g, b;

    float best_t = -1.0f;
    int   best_plane = -1;

    for (int i = 0; i < params.num_planes; ++i) {
        const PlaneParams& plane = params.planes[i];
        if (!plane.enabled) continue;

        float ray_orig_comp, ray_dir_comp;
        getRayPlaneComponents(ray_origin, ray_direction, plane.axis, ray_orig_comp, ray_dir_comp);

        if (fabsf(ray_dir_comp) > RAY_PARALLEL_THRESHOLD) {
            const float t = (plane.value - ray_orig_comp) / ray_dir_comp;
            if (t > 0.0f && (best_plane < 0 || t < best_t)) {
                best_t = t;
                best_plane = i;
            }
        }
    }

    if (best_plane >= 0) {
        const PlaneParams& plane = params.planes[best_plane];
        const float3 hit_point = ray_origin + ray_direction * best_t;

        float checker_u, checker_v;
        getCheckerboardCoordinates(hit_point, plane.axis, checker_u, checker_v);

        float3 base_color = getPlaneColor(plane, checker_u, checker_v);

        if (plane.texture_index >= 0 &&
            static_cast<unsigned int>(plane.texture_index) < params.num_textures) {
            float2 uv = make_float2(checker_u, checker_v);
            uv.x -= floorf(uv.x);
            uv.y -= floorf(uv.y);
            const float4 tex = tex2D<float4>(params.textures[plane.texture_index], uv.x, uv.y);
            base_color = make_float3(tex.x, tex.y, tex.z);
        }

        const float3 plane_normal = getPlaneNormal(plane);

        float3 total_color;

        if (plane.metallic > 0.0f) {
            const unsigned int depth = optixGetPayload_3();
            unsigned int reflect_r = 0, reflect_g = 0, reflect_b = 0;
            if (depth < static_cast<unsigned int>(params.max_ray_depth)) {
                traceReflectedRay(hit_point, ray_direction, plane_normal, depth,
                                  reflect_r, reflect_g, reflect_b);
            }
            const float3 tinted = make_float3(
                reflect_r / COLOR_BYTE_MAX * base_color.x,
                reflect_g / COLOR_BYTE_MAX * base_color.y,
                reflect_b / COLOR_BYTE_MAX * base_color.z
            );
            const float3 lighting = calculateLighting(hit_point, plane_normal, true);
            const float3 diffuse  = base_color * lighting;
            total_color = tinted * plane.metallic + diffuse * (1.0f - plane.metallic);
        } else {
            const float3 lighting = calculateLighting(hit_point, plane_normal, true);
            total_color = base_color * lighting;

            if (plane.specular > 0.0f && plane.roughness < 0.95f && params.num_lights > 0) {
                const Light& light = params.lights[0];
                float3 light_dir_normalized;
                float  attenuation;
                if (light.type == LightType::DIRECTIONAL) {
                    getDirectionalLightParams(light, light_dir_normalized, attenuation);
                } else {
                    getPointLightParams(light, hit_point, light_dir_normalized, attenuation);
                }
                const float3 view_dir   = normalize(ray_origin - hit_point);
                const float3 neg_light  = make_float3(-light_dir_normalized.x,
                                                      -light_dir_normalized.y,
                                                      -light_dir_normalized.z);
                const float  ndotl2     = 2.0f * dot(neg_light, plane_normal);
                const float3 refl_dir   = make_float3(
                    neg_light.x - ndotl2 * plane_normal.x,
                    neg_light.y - ndotl2 * plane_normal.y,
                    neg_light.z - ndotl2 * plane_normal.z);
                const float  spec_power = 2.0f / (plane.roughness * plane.roughness + 0.001f);
                const float  spec       = powf(fmaxf(0.0f, dot(view_dir, refl_dir)), spec_power)
                                          * plane.specular;
                total_color = total_color + make_float3(spec, spec, spec);
            }
        }

        total_color = total_color + base_color * plane.emission;

        r = static_cast<unsigned int>(total_color.x * COLOR_BYTE_MAX);
        g = static_cast<unsigned int>(total_color.y * COLOR_BYTE_MAX);
        b = static_cast<unsigned int>(total_color.z * COLOR_BYTE_MAX);
        applyFogInPlace(r, g, b, best_t);  // fog only when plane hit, not background
    } else {
        if (params.env_map_enabled)
            sampleEnvMap(r, g, b);
        else
            getBackgroundColor(r, g, b);
    }

    optixSetPayload_0(r);
    optixSetPayload_1(g);
    optixSetPayload_2(b);
}
