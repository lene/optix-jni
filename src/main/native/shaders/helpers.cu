// Beer-Lambert Law: I(d) = I₀ · exp(-α · d)
// Where:
//   I₀ = initial intensity
//   α = absorption coefficient (derived from color RGB and alpha)
//   d = distance traveled through medium
//
// Alpha interpretation (standard graphics convention):
//   alpha=1.0 → fully opaque (maximum absorption)
//   alpha=0.0 → fully transparent (no absorption)
//
// Color interpretation (RGB):
//   Each channel controls wavelength-dependent absorption
//   RGB(1,1,1) → no color tint (white/gray when opaque)
//   RGB(1,0,0) → absorbs green/blue, shows red (red tinted when opaque)

//==============================================================================
// Shadow Ray Tracing Helper
//==============================================================================

/**
 * Trace a shadow ray to determine visibility between hit point and light.
 *
 * Casts a shadow ray from the surface toward the light source to check
 * for occlusion. Handles transparent surfaces with Beer-Lambert absorption.
 *
 * @param hit_point Surface position where shadow ray originates
 * @param normal Surface normal (for offset to avoid self-intersection)
 * @param light_dir Direction to light source (normalized)
 * @return Shadow factor in range [0, 1] where 0=fully shadowed, 1=fully lit
 */
__device__ float traceShadowRay(
    const float3& hit_point,
    const float3& normal,
    const float3& light_dir
) {
    // Offset origin along normal to avoid shadow acne (self-intersection)
    const float3 shadow_origin = hit_point + normal * SHADOW_RAY_OFFSET;

    // Payload: 0.0 if ray hits (shadowed), 1.0 if ray misses (lit)
    // Transparent objects pack alpha-based attenuation
    unsigned int shadow_payload = 0;

    optixTrace(
        params.handle,
        shadow_origin,
        light_dir,
        SHADOW_RAY_OFFSET,           // tmin (avoid immediate intersection)
        SHADOW_RAY_MAX_DISTANCE,     // tmax (effectively infinite)
        0.0f,                         // rayTime
        OptixVisibilityMask(255),
        OPTIX_RAY_FLAG_NONE,         // Need closest hit to get alpha value
        1,                            // SBT offset (shadow ray type)
        2,                            // SBT stride (number of ray types)
        1,                            // missSBTIndex (shadow miss)
        shadow_payload
    );

    // Unpack shadow attenuation
    // shadow_attenuation: 0.0 = no occlusion (fully lit)
    //                     1.0 = full occlusion (fully shadowed)
    const float shadow_attenuation = __uint_as_float(shadow_payload);

    // Convert to shadow factor
    // shadow_factor: 1.0 = fully lit, 0.0 = fully shadowed
    const float shadow_factor = 1.0f - shadow_attenuation;

    // Track shadow ray statistics
    if (params.stats) {
        atomicAdd(&params.stats->shadow_rays, 1ULL);
        atomicAdd(&params.stats->total_rays, 1ULL);
    }

    return shadow_factor;
}

//==============================================================================
// Lighting Calculation Helpers
//==============================================================================

/**
 * Calculate light direction and attenuation for directional light.
 */
__device__ void getDirectionalLightParams(
    const Light& light,
    float3& light_dir,
    float& attenuation
) {
    light_dir = normalize(make_float3(
        light.direction[0],
        light.direction[1],
        light.direction[2]
    ));
    attenuation = 1.0f;  // No distance falloff
}

/**
 * Calculate light direction and attenuation for point light.
 */
__device__ void getPointLightParams(
    const Light& light,
    const float3& hit_point,
    float3& light_dir,
    float& attenuation
) {
    const float3 light_pos = make_float3(
        light.position[0],
        light.position[1],
        light.position[2]
    );
    const float3 to_light = light_pos - hit_point;
    const float distance = length(to_light);
    light_dir = to_light / distance;  // Normalize

    // Inverse-square law: I = I₀ / (1 + d²)
    attenuation = 1.0f / (1.0f + distance * distance);
}

/**
 * Calculate diffuse term (Lambertian: N · L).
 */
__device__ float calculateDiffuseTerm(
    const float3& normal,
    const float3& light_dir,
    bool double_sided
) {
    if (double_sided) {
        // Double-sided surface (e.g., plane): use absolute value
        const float raw_ndotl = dot(normal, light_dir);
        return fabsf(raw_ndotl);
    } else {
        // Single-sided surface (e.g., sphere): clamp to [0, ∞)
        return fmaxf(0.0f, dot(normal, light_dir));
    }
}

/**
 * Calculate total lighting contribution from all lights in scene.
 *
 * Accumulates lighting from multiple light sources (directional and point),
 * with support for shadows, distance attenuation, and ambient lighting.
 *
 * @param hit_point Surface position to light
 * @param normal Surface normal (normalized)
 * @param double_sided If true, use absolute value of NdotL (for planes)
 * @return Total lighting color (RGB) including ambient term, range [0, ∞)
 */
__device__ float3 calculateLighting(
    const float3& hit_point,
    const float3& normal,
    bool double_sided = false
) {
    float3 total_lighting = make_float3(0.0f, 0.0f, 0.0f);

    // Accumulate contribution from each light
    for (int light_idx = 0; light_idx < params.num_lights; ++light_idx) {
        const Light& light = params.lights[light_idx];

        // Calculate light direction and attenuation based on light type
        float3 light_dir;
        float attenuation;

        if (light.type == LightType::DIRECTIONAL) {
            getDirectionalLightParams(light, light_dir, attenuation);
        } else if (light.type == LightType::POINT) {
            getPointLightParams(light, hit_point, light_dir, attenuation);
        }

        // Calculate diffuse term (Lambertian: N · L)
        const float ndotl = calculateDiffuseTerm(normal, light_dir, double_sided);

        // Skip lights that don't contribute (sub-pixel threshold)
        if (ndotl <= MIN_LIGHTING_THRESHOLD) {
            continue;
        }

        // Trace shadow ray if shadows enabled
        const float shadow_factor = params.shadows_enabled
            ? traceShadowRay(hit_point, normal, light_dir)
            : 1.0f;

        // Accumulate light contribution
        const float3 light_color = make_float3(
            light.color[0],
            light.color[1],
            light.color[2]
        );

        total_lighting = total_lighting + light_color * light.intensity * attenuation * ndotl * shadow_factor;
    }

    // Add ambient lighting (prevents pure black shadows)
    const float3 ambient = make_float3(AMBIENT_LIGHT_FACTOR, AMBIENT_LIGHT_FACTOR, AMBIENT_LIGHT_FACTOR);

    // Combine: ambient + diffuse (energy conserving)
    return ambient + total_lighting * (1.0f - AMBIENT_LIGHT_FACTOR);
}

//==============================================================================
// Adaptive Antialiasing Helpers
//==============================================================================

/**
 * Calculate Euclidean color distance between two RGB colors.
 *
 * Computes the L2 norm in RGB color space for edge detection.
 * Higher values indicate greater color difference.
 *
 * @param c1 First color (RGB, 0-255)
 * @param c2 Second color (RGB, 0-255)
 * @return Euclidean distance normalized to [0, 1] range (max distance √3 ≈ 1.732)
 */
__device__ float colorDistance(
    const unsigned int r1, const unsigned int g1, const unsigned int b1,
    const unsigned int r2, const unsigned int g2, const unsigned int b2
) {
    // Normalize to [0, 1] range
    const float fr1 = static_cast<float>(r1) / COLOR_BYTE_MAX;
    const float fg1 = static_cast<float>(g1) / COLOR_BYTE_MAX;
    const float fb1 = static_cast<float>(b1) / COLOR_BYTE_MAX;

    const float fr2 = static_cast<float>(r2) / COLOR_BYTE_MAX;
    const float fg2 = static_cast<float>(g2) / COLOR_BYTE_MAX;
    const float fb2 = static_cast<float>(b2) / COLOR_BYTE_MAX;

    // Compute Euclidean distance
    const float dr = fr1 - fr2;
    const float dg = fg1 - fg2;
    const float db = fb1 - fb2;

    // Return normalized distance (max distance in RGB cube is √3)
    return sqrtf(dr * dr + dg * dg + db * db) / 1.732050808f;
}

/**
 * Trace a single ray and return the RGB color.
 *
 * Helper function to trace a ray through the scene and get back the color.
 *
 * @param ray_origin Ray origin point
 * @param ray_direction Ray direction (normalized)
 * @param r Output: red channel (0-255)
 * @param g Output: green channel (0-255)
 * @param b Output: blue channel (0-255)
 */
__device__ void traceRay(
    const float3& ray_origin,
    const float3& ray_direction,
    unsigned int& r,
    unsigned int& g,
    unsigned int& b
) {
    unsigned int depth = 0;
    optixTrace(
        params.handle,
        ray_origin,
        ray_direction,
        0.0f,
        MAX_RAY_DISTANCE,
        0.0f,
        OptixVisibilityMask(255),
        OPTIX_RAY_FLAG_NONE,
        0, 2, 0,  // ray_type=0 (primary), stride=2, miss_index=0
        r, g, b, depth
    );

    // Track AA ray statistics (this function is only called from AA subdivision)
    if (params.stats) {
        atomicAdd(&params.stats->aa_rays, 1ULL);
        atomicAdd(&params.stats->total_rays, 1ULL);
    }
}

/**
 * Stack entry for iterative adaptive antialiasing.
 * OptiX doesn't support recursive device functions, so we use an explicit stack.
 */
struct AAStackEntry {
    float center_u;
    float center_v;
    float half_size;
    int depth;
};

// Maximum stack size: at max depth 4 with 3×3 subdivision, we'd need at most 9^4 entries
// but we process breadth-first so max stack size is 9 * max_depth = 36
constexpr int AA_STACK_SIZE = 64;

/**
 * Iteratively subdivide pixel into 3×3 grid with adaptive sampling.
 * Uses explicit stack instead of recursion (required for OptiX).
 *
 * @param center_u Center U coordinate in normalized device coordinates
 * @param center_v Center V coordinate in normalized device coordinates
 * @param half_size Half-width of current pixel/sub-pixel in NDC
 * @param depth Current recursion depth (0 = top level)
 * @param camera_u Camera right vector
 * @param camera_v Camera up vector
 * @param camera_w Camera forward vector
 * @param ray_origin Camera position
 * @param sum_r Output: accumulated red (will be divided by sample count)
 * @param sum_g Output: accumulated green
 * @param sum_b Output: accumulated blue
 * @param sample_count Output: number of samples accumulated
 */
__device__ void subdividePixel(
    float init_center_u,
    float init_center_v,
    float init_half_size,
    int init_depth,
    const float3& camera_u,
    const float3& camera_v,
    const float3& camera_w,
    const float3& ray_origin,
    unsigned long long& sum_r,
    unsigned long long& sum_g,
    unsigned long long& sum_b,
    unsigned int& sample_count
) {
    // Explicit stack for iterative processing
    AAStackEntry stack[AA_STACK_SIZE];
    int stack_top = 0;

    // Push initial entry
    stack[stack_top++] = {init_center_u, init_center_v, init_half_size, init_depth};

    while (stack_top > 0) {
        // Pop entry from stack
        const AAStackEntry entry = stack[--stack_top];
        const float center_u = entry.center_u;
        const float center_v = entry.center_v;
        const float half_size = entry.half_size;
        const int depth = entry.depth;

        // Sample 3×3 grid within current region
        unsigned int samples[9][3];  // RGB for each of 9 samples
        float max_diff = 0.0f;

        // Grid positions: -1, 0, +1 (in units of half_size/1.5 for 3×3 subdivision)
        const float step = half_size / 1.5f;

        for (int iy = 0; iy < 3; ++iy) {
            for (int ix = 0; ix < 3; ++ix) {
                const int idx = iy * 3 + ix;

                // Calculate sample position
                const float u = center_u + (ix - 1) * step;
                const float v = center_v + (iy - 1) * step;

                // Construct ray direction
                const float3 ray_dir = normalize(camera_u * u + camera_v * v + camera_w);

                // Trace ray
                traceRay(ray_origin, ray_dir, samples[idx][0], samples[idx][1], samples[idx][2]);

                // Check color difference with neighbors for edge detection
                if (ix > 0) {
                    const int left_idx = iy * 3 + (ix - 1);
                    const float diff = colorDistance(
                        samples[idx][0], samples[idx][1], samples[idx][2],
                        samples[left_idx][0], samples[left_idx][1], samples[left_idx][2]
                    );
                    max_diff = fmaxf(max_diff, diff);
                }

                if (iy > 0) {
                    const int top_idx = (iy - 1) * 3 + ix;
                    const float diff = colorDistance(
                        samples[idx][0], samples[idx][1], samples[idx][2],
                        samples[top_idx][0], samples[top_idx][1], samples[top_idx][2]
                    );
                    max_diff = fmaxf(max_diff, diff);
                }
            }
        }

        // Edge detected and can recurse further?
        const bool should_subdivide = (max_diff > params.aa_threshold) && (depth < params.aa_max_depth);

        if (should_subdivide && stack_top + 9 <= AA_STACK_SIZE) {
            // Push 9 sub-pixel tasks onto stack
            const float new_half_size = half_size / 3.0f;

            for (int iy = 0; iy < 3; ++iy) {
                for (int ix = 0; ix < 3; ++ix) {
                    const float sub_u = center_u + (ix - 1) * step;
                    const float sub_v = center_v + (iy - 1) * step;

                    stack[stack_top++] = {sub_u, sub_v, new_half_size, depth + 1};
                }
            }
        } else {
            // No edge, max depth reached, or stack full - accumulate these 9 samples
            for (int i = 0; i < 9; ++i) {
                sum_r += samples[i][0];
                sum_g += samples[i][1];
                sum_b += samples[i][2];
                sample_count++;
            }
        }
    }
}

//==============================================================================
// Custom Sphere Intersection Program
// From NVIDIA OptiX SDK 9.0 sphere.cu
// Uses normalized direction + length correction for numerical stability
//==============================================================================
extern "C" __global__ void __intersection__sphere()
{
    const HitGroupData* hit_data = reinterpret_cast<HitGroupData*>(optixGetSbtDataPointer());

    const float3 ray_orig = optixGetWorldRayOrigin();
    const float3 ray_dir  = optixGetWorldRayDirection();
    const float  ray_tmin = optixGetRayTmin();
    const float  ray_tmax = optixGetRayTmax();

    const float3 center = make_float3(
        hit_data->sphere_center[0],
        hit_data->sphere_center[1],
        hit_data->sphere_center[2]
    );
    const float radius = hit_data->sphere_radius;

    // SDK approach: normalize ray direction and track length separately
    // This provides better numerical stability
    const float3 O = ray_orig - center;
    const float  l = 1.0f / length(ray_dir);  // Inverse length
    const float3 D = ray_dir * l;             // Normalized direction

    // Ray-sphere intersection with normalized direction
    float b    = dot(O, D);
    float c    = dot(O, O) - radius * radius;
    float disc = b * b - c;

    if (disc > 0.0f)
    {
        float sdisc        = sqrtf(disc);
        float root1        = (-b - sdisc);  // Near intersection (in normalized space)
        float root11       = 0.0f;
        bool  check_second = true;

        // Numerical refinement for large distances (SDK feature)
        const bool do_refine = fabsf(root1) > (SPHERE_INTERSECTION_REFINEMENT_THRESHOLD * radius);
        if (do_refine)
        {
            // Refine root1 for better accuracy
            float3 O1 = O + root1 * D;
            b         = dot(O1, D);
            c         = dot(O1, O1) - radius * radius;
            disc      = b * b - c;

            if (disc > 0.0f)
            {
                sdisc  = sqrtf(disc);
                root11 = (-b - sdisc);
            }
        }

        // Report near intersection (entry)
        float  t;
        float3 normal;
        t = (root1 + root11) * l;  // Convert back to real t using inverse length
        if (t > ray_tmin && t < ray_tmax)
        {
            normal = (O + (root1 + root11) * D) / radius;  // Outward normal
            if (optixReportIntersection(t, 0,
                __float_as_uint(normal.x),
                __float_as_uint(normal.y),
                __float_as_uint(normal.z),
                __float_as_uint(radius)))
                check_second = false;
        }

        // Report far intersection (exit)
        if (check_second)
        {
            float root2 = (-b + sdisc) + (do_refine ? root1 : 0);
            t           = root2 * l;  // Convert back to real t
            normal      = (O + root2 * D) / radius;  // Outward normal
            if (t > ray_tmin && t < ray_tmax)
                optixReportIntersection(t, 1,  // hit_kind = 1 for EXIT
                    __float_as_uint(normal.x),
                    __float_as_uint(normal.y),
                    __float_as_uint(normal.z),
                    __float_as_uint(radius));
        }
    }
}
