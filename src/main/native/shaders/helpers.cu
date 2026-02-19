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
    const float shadow_factor = SBTConstants::SHADOW_FACTOR_FULLY_LIT - shadow_attenuation;

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
    // light.direction represents direction TO the light source
    // Use as-is for both diffuse lighting (N·L) and shadow rays
    light_dir = normalize(make_float3(
        light.direction[0],
        light.direction[1],
        light.direction[2]
    ));
    attenuation = RenderingConstants::DISTANCE_FALLOFF_NONE;  // No distance falloff
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
    attenuation = RenderingConstants::DISTANCE_FALLOFF_BASE / (RenderingConstants::DISTANCE_FALLOFF_BASE + distance * distance);
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
        return fmaxf(RenderingConstants::DOT_PRODUCT_ZERO_THRESHOLD, dot(normal, light_dir));
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
 * @param skip_shadows If true, skip shadow ray tracing (for cylinder fallback to avoid recursion)
 * @return Total lighting color (RGB) including ambient term, range [0, ∞)
 */
__device__ float3 calculateLighting(
    const float3& hit_point,
    const float3& normal,
    bool double_sided = false,
    bool skip_shadows = false
) {
    float3 total_lighting = make_float3(RenderingConstants::COLOR_BLACK, RenderingConstants::COLOR_BLACK, RenderingConstants::COLOR_BLACK);

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

        // Trace shadow ray if shadows enabled (and not skipped for recursion avoidance)
        const float shadow_factor = (params.shadows_enabled && !skip_shadows)
            ? traceShadowRay(hit_point, normal, light_dir)
            : SBTConstants::SHADOW_FACTOR_FULLY_LIT;

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
    return ambient + total_lighting * RenderingConstants::DIFFUSE_BLEND_FACTOR;
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

    // Return normalized distance (max distance in RGB cube is sqrt(3))
    return sqrtf(dr * dr + dg * dg + db * db) / SQRT_3;
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
        SBTConstants::RAY_TYPE_PRIMARY, SBTConstants::STRIDE_RAY_TYPES, SBTConstants::MISS_PRIMARY,  // ray_type=0 (primary), stride=2, miss_index=0
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
 * Sample 3x3 grid within a pixel region and detect edges.
 *
 * Traces 9 rays in a grid pattern and computes the maximum color difference
 * between adjacent samples to detect edges requiring further subdivision.
 *
 * @param center_u Center U coordinate in normalized device coordinates
 * @param center_v Center V coordinate in normalized device coordinates
 * @param half_size Half-width of current pixel/sub-pixel in NDC
 * @param camera_u Camera right vector
 * @param camera_v Camera up vector
 * @param camera_w Camera forward vector
 * @param ray_origin Camera position
 * @param samples Output: 9x3 array of RGB samples (0-255 per channel)
 * @param step Output: grid step size (for subdivision positioning)
 * @return Maximum color difference between adjacent samples [0, 1]
 */
__device__ float sampleGridAndDetectEdges(
    float center_u,
    float center_v,
    float half_size,
    const float3& camera_u,
    const float3& camera_v,
    const float3& camera_w,
    const float3& ray_origin,
    unsigned int samples[9][3],
    float& step
) {
    float max_diff = 0.0f;

    // Grid positions: -1, 0, +1 (in units of half_size/1.5 for 3×3 subdivision)
    step = half_size / RayTracingConstants::AA_GRID_SUBDIVISION_DIVISOR;

    for (int iy = 0; iy < RayTracingConstants::AA_SUBDIVISION_FACTOR; ++iy) {
        for (int ix = 0; ix < RayTracingConstants::AA_SUBDIVISION_FACTOR; ++ix) {
            const int idx = iy * RayTracingConstants::AA_SUBDIVISION_FACTOR + ix;

            // Calculate sample position
            const float u = center_u + (ix - 1) * step;
            const float v = center_v + (iy - 1) * step;

            // Construct ray direction
            const float3 ray_dir = normalize(camera_u * u + camera_v * v + camera_w);

            // Trace ray
            traceRay(ray_origin, ray_dir, samples[idx][0], samples[idx][1], samples[idx][2]);

            // Check color difference with neighbors for edge detection
            if (ix > 0) {
                const int left_idx = iy * RayTracingConstants::AA_SUBDIVISION_FACTOR + (ix - 1);
                const float diff = colorDistance(
                    samples[idx][0], samples[idx][1], samples[idx][2],
                    samples[left_idx][0], samples[left_idx][1], samples[left_idx][2]
                );
                max_diff = fmaxf(max_diff, diff);
            }

            if (iy > 0) {
                const int top_idx = (iy - 1) * RayTracingConstants::AA_SUBDIVISION_FACTOR + ix;
                const float diff = colorDistance(
                    samples[idx][0], samples[idx][1], samples[idx][2],
                    samples[top_idx][0], samples[top_idx][1], samples[top_idx][2]
                );
                max_diff = fmaxf(max_diff, diff);
            }
        }
    }

    return max_diff;
}

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

        // Sample 3×3 grid and detect edges
        unsigned int samples[9][3];  // RGB for each of 9 samples
        float step;
        const float max_diff = sampleGridAndDetectEdges(
            center_u, center_v, half_size,
            camera_u, camera_v, camera_w, ray_origin,
            samples, step
        );

        // Edge detected and can recurse further?
        const bool should_subdivide = (max_diff > params.aa_threshold) && (depth < params.aa_max_depth);
        const bool stack_has_space = stack_top + RayTracingConstants::AA_SUBPIXEL_COUNT <= AA_STACK_SIZE;

        if (should_subdivide && stack_has_space) {
            // Push 9 sub-pixel tasks onto stack
            const float new_half_size = half_size / static_cast<float>(RayTracingConstants::AA_SUBDIVISION_FACTOR);

            for (int iy = 0; iy < RayTracingConstants::AA_SUBDIVISION_FACTOR; ++iy) {
                for (int ix = 0; ix < RayTracingConstants::AA_SUBDIVISION_FACTOR; ++ix) {
                    const float sub_u = center_u + (ix - 1) * step;
                    const float sub_v = center_v + (iy - 1) * step;

                    stack[stack_top++] = {sub_u, sub_v, new_half_size, depth + 1};
                }
            }
        } else {
            // Track when subdivision was skipped due to stack overflow
            if (should_subdivide && !stack_has_space && params.stats) {
                atomicAdd(&params.stats->aa_stack_overflows, 1ULL);
            }
            // No edge, max depth reached, or stack full - accumulate these 9 samples
            for (int i = 0; i < RayTracingConstants::AA_SUBPIXEL_COUNT; ++i) {
                sum_r += samples[i][0];
                sum_g += samples[i][1];
                sum_b += samples[i][2];
                sample_count++;
            }
        }
    }
}

//==============================================================================
// Shared Physics Functions (used by both sphere and triangle hit shaders)
//==============================================================================

/**
 * Handle fully transparent surface (alpha < threshold).
 * Ray continues through as if surface doesn't exist.
 *
 * @param hit_point Surface intersection point
 * @param ray_direction Incoming ray direction
 * @param depth Current recursion depth
 */
__device__ void handleFullyTransparent(
    const float3& hit_point,
    const float3& ray_direction,
    unsigned int depth
) {
    const float3 continue_origin = hit_point + ray_direction * CONTINUATION_RAY_OFFSET;
    unsigned int continue_r = 0, continue_g = 0, continue_b = 0;
    unsigned int next_depth = depth;  // Don't increment depth for transparent pass-through

    optixTrace(
        params.handle,
        continue_origin,
        ray_direction,  // Continue in same direction
        CONTINUATION_RAY_OFFSET,
        MAX_RAY_DISTANCE,
        0.0f,
        OptixVisibilityMask(255),
        OPTIX_RAY_FLAG_NONE,
        SBTConstants::RAY_TYPE_PRIMARY, SBTConstants::STRIDE_RAY_TYPES, SBTConstants::MISS_PRIMARY,  // ray_type=0 (primary), stride=2, miss_index=0
        continue_r, continue_g, continue_b, next_depth
    );

    optixSetPayload_0(continue_r);
    optixSetPayload_1(continue_g);
    optixSetPayload_2(continue_b);
}

/**
 * Trace a continuation ray through a surface and return the color.
 *
 * Similar to handleFullyTransparent, but returns color via output parameters
 * instead of setting payloads. Used for coverage alpha blending.
 *
 * @param hit_point Surface intersection point
 * @param ray_direction Incoming ray direction
 * @param depth Current recursion depth (not incremented for pass-through)
 * @param r/g/b Output: color from continuation ray (0-255)
 */
// Small tmin for coverage-alpha continuation rays.
// Must be smaller than the skin face offset (SKIN_NORMAL_OFFSET in SpongeByVolume) so the
// continuation ray can reach the sponge face sitting just behind the skin face.
// Using 0.0001f (10x smaller than CONTINUATION_RAY_OFFSET) to allow hitting the sponge
// face at ~0.003 distance while still avoiding self-intersection on the skin face itself.
constexpr float COVERAGE_CONTINUATION_OFFSET = 0.0001f;

__device__ void traceContinuationRay(
    const float3& hit_point,
    const float3& ray_direction,
    unsigned int depth,
    unsigned int& r,
    unsigned int& g,
    unsigned int& b
) {
    const float3 continue_origin = hit_point + ray_direction * COVERAGE_CONTINUATION_OFFSET;
    unsigned int next_depth = depth;  // Don't increment depth for transparent pass-through

    optixTrace(
        params.handle,
        continue_origin,
        ray_direction,
        COVERAGE_CONTINUATION_OFFSET,
        MAX_RAY_DISTANCE,
        0.0f,
        OptixVisibilityMask(255),
        OPTIX_RAY_FLAG_NONE,
        SBTConstants::RAY_TYPE_PRIMARY, SBTConstants::STRIDE_RAY_TYPES, SBTConstants::MISS_PRIMARY,
        r, g, b, next_depth
    );
}

/**
 * Compute diffuse lighting color for opaque surface.
 * Returns RGB color values (0-255 range) without setting payload.
 * Used for blending metallic and diffuse contributions.
 *
 * @param hit_point Surface intersection point
 * @param normal Surface normal (pointing toward ray origin)
 * @param material_color RGBA material color
 * @param diffuse_r Output: Red component (0-255)
 * @param diffuse_g Output: Green component (0-255)
 * @param diffuse_b Output: Blue component (0-255)
 */
__device__ void computeDiffuseColor(
    const float3& hit_point,
    const float3& normal,
    const float4& material_color,
    unsigned int& diffuse_r,
    unsigned int& diffuse_g,
    unsigned int& diffuse_b
) {
    const float3 lighting = calculateLighting(hit_point, normal);

    const float3 surface_color = make_float3(
        material_color.x,
        material_color.y,
        material_color.z
    );

    const float3 lit_color = make_float3(
        surface_color.x * lighting.x,
        surface_color.y * lighting.y,
        surface_color.z * lighting.z
    );

    diffuse_r = static_cast<unsigned int>(fminf(lit_color.x * RayTracingConstants::COLOR_BYTE_MAX, RayTracingConstants::COLOR_BYTE_MAX));
    diffuse_g = static_cast<unsigned int>(fminf(lit_color.y * RayTracingConstants::COLOR_BYTE_MAX, RayTracingConstants::COLOR_BYTE_MAX));
    diffuse_b = static_cast<unsigned int>(fminf(lit_color.z * RayTracingConstants::COLOR_BYTE_MAX, RayTracingConstants::COLOR_BYTE_MAX));
}

/**
 * Add emission to RGB color values (0-255 range).
 * Emission is multiplied by the material color to preserve color while adding glow.
 *
 * @param r/g/b Input/output: RGB values (0-255)
 * @param color Material color (0.0-1.0)
 * @param emission Emission intensity (0.0-10.0)
 */
__device__ void addEmissionToColor(
    unsigned int& r, unsigned int& g, unsigned int& b,
    const float4& color,
    float emission
) {
    if (emission > 0.0f) {
        float emissive_r = emission * color.x * RayTracingConstants::COLOR_SCALE_FACTOR;
        float emissive_g = emission * color.y * RayTracingConstants::COLOR_SCALE_FACTOR;
        float emissive_b = emission * color.z * RayTracingConstants::COLOR_SCALE_FACTOR;

        r = min(r + static_cast<unsigned int>(emissive_r), 255u);
        g = min(g + static_cast<unsigned int>(emissive_g), 255u);
        b = min(b + static_cast<unsigned int>(emissive_b), 255u);
    }
}

/**
 * Handle fully opaque surface (alpha >= threshold).
 * Solid surface with diffuse shading.
 *
 * @param hit_point Surface intersection point
 * @param normal Surface normal (pointing toward ray origin)
 * @param material_color RGBA material color
 * @param emission Emission intensity (0.0-10.0)
 */
__device__ void handleFullyOpaque(
    const float3& hit_point,
    const float3& normal,
    const float4& material_color,
    float emission = 0.0f
) {
    unsigned int r, g, b;
    computeDiffuseColor(hit_point, normal, material_color, r, g, b);
    addEmissionToColor(r, g, b, material_color, emission);
    optixSetPayload_0(r);
    optixSetPayload_1(g);
    optixSetPayload_2(b);
}

/**
 * Trace final non-recursive ray when max depth is reached.
 * Avoids black artifacts from depth cutoff by tracing one more reflection.
 *
 * @param hit_point Surface intersection point
 * @param ray_direction Incoming ray direction
 * @param normal Surface normal (pointing toward ray origin)
 */
__device__ void traceFinalNonRecursiveRay(
    const float3& hit_point,
    const float3& ray_direction,
    const float3& normal
) {
    const float cos_theta = fabsf(dot(ray_direction, normal));
    const float3 reflect_dir = make_float3(
        ray_direction.x - RenderingConstants::REFLECTION_SCALE * cos_theta * normal.x,
        ray_direction.y - RenderingConstants::REFLECTION_SCALE * cos_theta * normal.y,
        ray_direction.z - RenderingConstants::REFLECTION_SCALE * cos_theta * normal.z
    );

    unsigned int final_r = 0, final_g = 0, final_b = 0;
    unsigned int final_depth = MAX_TRACE_DEPTH;  // Keep at max depth to prevent recursion
    const float3 final_origin = hit_point + reflect_dir * CONTINUATION_RAY_OFFSET;

    optixTrace(
        params.handle,
        final_origin,
        reflect_dir,
        CONTINUATION_RAY_OFFSET,
        MAX_RAY_DISTANCE,
        0.0f,
        OptixVisibilityMask(255),
        OPTIX_RAY_FLAG_NONE,
        SBTConstants::RAY_TYPE_PRIMARY, SBTConstants::STRIDE_RAY_TYPES, SBTConstants::MISS_PRIMARY,  // ray_type=0 (primary), stride=2, miss_index=0
        final_r, final_g, final_b, final_depth
    );

    optixSetPayload_0(final_r);
    optixSetPayload_1(final_g);
    optixSetPayload_2(final_b);
}

/**
 * Compute Fresnel reflectance using Schlick approximation.
 *
 * @param ray_direction Incoming ray direction
 * @param normal Surface normal (pointing toward ray origin)
 * @param entering True if ray is entering the medium
 * @param material_ior Index of refraction of the material
 * @return Fresnel reflectance coefficient [0, 1]
 */
__device__ float computeFresnelReflectance(
    const float3& ray_direction,
    const float3& normal,
    bool entering,
    float material_ior
) {
    const float n1 = entering ? RenderingConstants::VACUUM_IOR : material_ior;
    const float n2 = entering ? material_ior : RenderingConstants::VACUUM_IOR;
    const float r0 = (n1 - n2) / (n1 + n2);
    const float R0 = r0 * r0;
    const float cos_theta = fabsf(dot(ray_direction, normal));
    const float one_minus_cos = RenderingConstants::FRESNEL_ONE_MINUS_COS - cos_theta;
    return R0 + RenderingConstants::FRESNEL_ONE_MINUS_R0 * one_minus_cos * one_minus_cos * one_minus_cos * one_minus_cos * one_minus_cos;
}

//==============================================================================
// Thin-Film Interference (Airy Reflectance)
//==============================================================================

// CIE 1931 2° observer XYZ color matching functions, sampled at 16 wavelengths
// from 380nm to 780nm (26.67nm spacing). Used to convert spectral reflectance to RGB.
__device__ __constant__ float CIE_WAVELENGTHS[16] = {
    380.0f, 406.7f, 433.3f, 460.0f, 486.7f, 513.3f, 540.0f, 566.7f,
    593.3f, 620.0f, 646.7f, 673.3f, 700.0f, 726.7f, 753.3f, 780.0f
};

__device__ __constant__ float CIE_X[16] = {
    0.0014f, 0.0146f, 0.0913f, 0.2900f, 0.0966f, 0.0175f, 0.2080f, 0.5820f,
    0.9163f, 1.0263f, 0.7570f, 0.4256f, 0.1842f, 0.0563f, 0.0152f, 0.0026f
};

__device__ __constant__ float CIE_Y[16] = {
    0.0000f, 0.0004f, 0.0045f, 0.0380f, 0.1360f, 0.3240f, 0.6310f, 0.9149f,
    0.9786f, 0.8310f, 0.5520f, 0.2990f, 0.1300f, 0.0392f, 0.0104f, 0.0017f
};

__device__ __constant__ float CIE_Z[16] = {
    0.0065f, 0.0709f, 0.4652f, 1.5220f, 0.5688f, 0.0746f, 0.0087f, 0.0017f,
    0.0008f, 0.0003f, 0.0001f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f
};

/**
 * Convert CIE XYZ color to linear sRGB using D65 illuminant matrix.
 */
__device__ float3 xyzToLinearRGB(float X, float Y, float Z) {
    return make_float3(
         3.2406f * X - 1.5372f * Y - 0.4986f * Z,
        -0.9689f * X + 1.8758f * Y + 0.0415f * Z,
         0.0557f * X - 0.2040f * Y + 1.0570f * Z
    );
}

/**
 * Compute thin-film interference reflectance using the Airy formula.
 *
 * Models a free-standing film (air/film/air) producing wavelength-dependent
 * Fresnel reflectance that creates iridescent colors (soap bubbles, oil slicks).
 *
 * Physics:
 *   1. Snell's law: sin(θ_t) = sin(θ_i) / n_film
 *   2. Phase difference: δ = 4π · n_film · d · cos(θ_t) / λ
 *   3. Fresnel coefficient at air/film interface (averaged s+p polarizations)
 *   4. Airy reflectance: R(λ) = 2r²(1 - cos δ) / (1 + r⁴ - 2r² cos δ)
 *
 * Samples 16 wavelengths across 380-780nm, converted to RGB via CIE 1931 XYZ.
 *
 * @param cos_theta_i Cosine of incidence angle (dot of ray with normal)
 * @param film_ior Refractive index of the film
 * @param thickness_nm Film thickness in nanometers
 * @return RGB reflectance (each channel 0.0-1.0)
 */
__device__ float3 computeThinFilmReflectance(
    float cos_theta_i,
    float film_ior,
    float thickness_nm
) {
    // Clamp cosine to valid range
    cos_theta_i = fmaxf(cos_theta_i, 0.001f);

    const float sin_theta_i = sqrtf(1.0f - cos_theta_i * cos_theta_i);

    // Snell's law: sin(θ_t) = sin(θ_i) / n_film
    const float sin_theta_t = sin_theta_i / film_ior;
    // Check for total internal reflection (shouldn't happen for air→film, but be safe)
    if (sin_theta_t >= 1.0f) {
        return make_float3(1.0f, 1.0f, 1.0f);
    }
    const float cos_theta_t = sqrtf(1.0f - sin_theta_t * sin_theta_t);

    // Fresnel coefficients at air/film interface (n1=1.0 air, n2=film_ior)
    const float n1 = 1.0f;
    const float n2 = film_ior;
    const float rs = (n1 * cos_theta_i - n2 * cos_theta_t) / (n1 * cos_theta_i + n2 * cos_theta_t);
    const float rp = (n2 * cos_theta_i - n1 * cos_theta_t) / (n2 * cos_theta_i + n1 * cos_theta_t);
    // Average of s and p polarizations
    const float r = 0.5f * (rs + rp);
    const float r2 = r * r;
    const float r4 = r2 * r2;

    // Integrate spectral reflectance weighted by CIE color matching functions
    float X = 0.0f, Y = 0.0f, Z = 0.0f;
    const float delta_lambda = 26.67f;  // nm spacing between samples

    for (int i = 0; i < 16; i++) {
        const float lambda = CIE_WAVELENGTHS[i];

        // Phase difference: δ = 4π · n_film · d · cos(θ_t) / λ
        const float delta = 4.0f * M_PIf * film_ior * thickness_nm * cos_theta_t / lambda;
        const float cos_delta = cosf(delta);

        // Airy formula: R(λ) = 2r²(1 - cos δ) / (1 + r⁴ - 2r² cos δ)
        const float denom = 1.0f + r4 - 2.0f * r2 * cos_delta;
        const float R_lambda = (denom > 1e-8f)
            ? 2.0f * r2 * (1.0f - cos_delta) / denom
            : 0.0f;

        // Accumulate XYZ weighted by spectral reflectance
        X += R_lambda * CIE_X[i] * delta_lambda;
        Y += R_lambda * CIE_Y[i] * delta_lambda;
        Z += R_lambda * CIE_Z[i] * delta_lambda;
    }

    // Normalize: divide by integral of Y (luminance) over visible spectrum
    // For our 16 CIE_Y samples × 26.67nm spacing, sum ≈ 106.5
    const float Y_integral = 106.5f;
    X /= Y_integral;
    Y /= Y_integral;
    Z /= Y_integral;

    // Convert XYZ to linear sRGB
    float3 rgb = xyzToLinearRGB(X, Y, Z);

    // Clamp to [0, 1] — out-of-gamut colors get clamped
    rgb.x = fmaxf(0.0f, fminf(1.0f, rgb.x));
    rgb.y = fmaxf(0.0f, fminf(1.0f, rgb.y));
    rgb.z = fmaxf(0.0f, fminf(1.0f, rgb.z));

    return rgb;
}

//==============================================================================
// RGB Fresnel Color Blending (for thin-film interference)
//==============================================================================

/**
 * Blend reflected and refracted colors using per-channel RGB Fresnel and set payloads.
 *
 * Like blendFresnelColorsAndSetPayload but takes float3 fresnel_rgb instead of scalar,
 * enabling wavelength-dependent reflectance from thin-film interference.
 *
 * @param fresnel_rgb Per-channel Fresnel reflectance [0, 1] per R/G/B
 * @param reflect_r/g/b Reflected ray color (0-255)
 * @param refract_color Refracted ray color after Beer-Lambert (0.0-1.0 per channel)
 * @param material_color Material color for emission tinting
 * @param emission Emission intensity (0.0-10.0)
 */
__device__ void blendFresnelColorsRGBAndSetPayload(
    const float3& fresnel_rgb,
    unsigned int reflect_r,
    unsigned int reflect_g,
    unsigned int reflect_b,
    const float3& refract_color,
    const float4& material_color = make_float4(1.0f, 1.0f, 1.0f, 1.0f),
    float emission = 0.0f
) {
    // Convert reflected color to normalized float
    const float3 reflect_color = make_float3(
        static_cast<float>(reflect_r) / RayTracingConstants::COLOR_BYTE_MAX,
        static_cast<float>(reflect_g) / RayTracingConstants::COLOR_BYTE_MAX,
        static_cast<float>(reflect_b) / RayTracingConstants::COLOR_BYTE_MAX
    );

    // Blend using per-channel Fresnel coefficients
    const float3 final_color = make_float3(
        fresnel_rgb.x * reflect_color.x + (1.0f - fresnel_rgb.x) * refract_color.x,
        fresnel_rgb.y * reflect_color.y + (1.0f - fresnel_rgb.y) * refract_color.y,
        fresnel_rgb.z * reflect_color.z + (1.0f - fresnel_rgb.z) * refract_color.z
    );

    // Convert to integer
    unsigned int r = static_cast<unsigned int>(fminf(final_color.x * RayTracingConstants::COLOR_BYTE_MAX, RayTracingConstants::COLOR_BYTE_MAX));
    unsigned int g = static_cast<unsigned int>(fminf(final_color.y * RayTracingConstants::COLOR_BYTE_MAX, RayTracingConstants::COLOR_BYTE_MAX));
    unsigned int b = static_cast<unsigned int>(fminf(final_color.z * RayTracingConstants::COLOR_BYTE_MAX, RayTracingConstants::COLOR_BYTE_MAX));

    // Add emission
    addEmissionToColor(r, g, b, material_color, emission);

    // Set payloads
    optixSetPayload_0(r);
    optixSetPayload_1(g);
    optixSetPayload_2(b);
}

/**
 * Trace reflected ray and return color components.
 *
 * @param hit_point Surface intersection point
 * @param ray_direction Incoming ray direction
 * @param normal Surface normal (pointing toward ray origin)
 * @param depth Current recursion depth
 * @param reflect_r/g/b Output: reflected ray color (0-255)
 */
__device__ void traceReflectedRay(
    const float3& hit_point,
    const float3& ray_direction,
    const float3& normal,
    unsigned int depth,
    unsigned int& reflect_r,
    unsigned int& reflect_g,
    unsigned int& reflect_b
) {
    // Reflection formula: R = I - 2 * dot(I, N) * N
    // Note: dot(I, N) is typically negative for front-facing hits, which is correct
    const float dot_in = dot(ray_direction, normal);
    const float3 reflect_dir = make_float3(
        ray_direction.x - 2.0f * dot_in * normal.x,
        ray_direction.y - 2.0f * dot_in * normal.y,
        ray_direction.z - 2.0f * dot_in * normal.z
    );

    if (params.stats) {
        atomicAdd(&params.stats->reflected_rays, 1ULL);
        atomicAdd(&params.stats->total_rays, 1ULL);
    }

    const float3 reflect_origin = hit_point + reflect_dir * CONTINUATION_RAY_OFFSET;
    unsigned int next_depth = depth + 1;

    optixTrace(
        params.handle,
        reflect_origin,
        reflect_dir,
        CONTINUATION_RAY_OFFSET,
        MAX_RAY_DISTANCE,
        0.0f,
        OptixVisibilityMask(255),
        OPTIX_RAY_FLAG_NONE,
        SBTConstants::RAY_TYPE_PRIMARY, SBTConstants::STRIDE_RAY_TYPES, SBTConstants::MISS_PRIMARY,  // ray_type=0 (primary), stride=2, miss_index=0
        reflect_r, reflect_g, reflect_b, next_depth
    );
}

/**
 * Trace refracted ray using Snell's law.
 * Returns false if total internal reflection occurs.
 *
 * @param hit_point Surface intersection point
 * @param ray_direction Incoming ray direction
 * @param normal Surface normal (pointing toward ray origin)
 * @param entering True if ray is entering the medium
 * @param depth Current recursion depth
 * @param material_ior Index of refraction of the material
 * @param refract_r/g/b Output: refracted ray color (0-255)
 * @return True if refraction occurred, false if total internal reflection
 */
__device__ bool traceRefractedRay(
    const float3& hit_point,
    const float3& ray_direction,
    const float3& normal,
    bool entering,
    unsigned int depth,
    float material_ior,
    unsigned int& refract_r,
    unsigned int& refract_g,
    unsigned int& refract_b
) {
    const float n1 = entering ? 1.0f : material_ior;
    const float n2 = entering ? material_ior : 1.0f;
    const float eta = n1 / n2;
    const float cos_theta = fabsf(dot(ray_direction, normal));
    const float k = 1.0f - eta * eta * (1.0f - cos_theta * cos_theta);

    if (k < 0.0f) {
        return false;  // Total internal reflection
    }

    if (params.stats) {
        atomicAdd(&params.stats->refracted_rays, 1ULL);
        atomicAdd(&params.stats->total_rays, 1ULL);
    }

    const float coeff = eta * cos_theta - sqrtf(k);
    const float3 refract_dir = make_float3(
        eta * ray_direction.x + coeff * normal.x,
        eta * ray_direction.y + coeff * normal.y,
        eta * ray_direction.z + coeff * normal.z
    );

    const float3 refract_origin = hit_point + refract_dir * CONTINUATION_RAY_OFFSET;
    unsigned int next_depth = depth + 1;

    optixTrace(
        params.handle,
        refract_origin,
        refract_dir,
        CONTINUATION_RAY_OFFSET,
        MAX_RAY_DISTANCE,
        0.0f,
        OptixVisibilityMask(255),
        OPTIX_RAY_FLAG_NONE,
        SBTConstants::RAY_TYPE_PRIMARY, SBTConstants::STRIDE_RAY_TYPES, SBTConstants::MISS_PRIMARY,  // ray_type=0 (primary), stride=2, miss_index=0
        refract_r, refract_g, refract_b, next_depth
    );

    return true;
}

/**
 * Handle metallic opaque surface with blended metallic/diffuse rendering.
 *
 * Combines reflection (metallic component) with diffuse shading (non-metallic component)
 * using the formula: final = metallic * tinted_reflection + (1-metallic) * diffuse
 *
 * This implements physically-based rendering where metallic materials reflect their
 * environment tinted by their color (e.g., gold reflects with yellow tint, copper with
 * orange tint), while non-metallic portions show standard diffuse lighting.
 *
 * @param hit_point Surface hit point
 * @param ray_direction Incoming ray direction
 * @param normal Surface normal (facing toward ray)
 * @param material_color Material RGBA color
 * @param metallic Metallic value [0,1] (0=fully diffuse, 1=fully metallic)
 * @param depth Current ray depth
 * @param emission Emission intensity (0.0-10.0)
 */
__device__ void handleMetallicOpaque(
    const float3& hit_point,
    const float3& ray_direction,
    const float3& normal,
    const float4& material_color,
    float metallic,
    unsigned int depth,
    float emission = 0.0f
) {
    // If at max depth, trace final non-recursive ray
    if (depth >= MAX_TRACE_DEPTH) {
        traceFinalNonRecursiveRay(hit_point, ray_direction, normal);
        return;
    }

    // Trace reflection ray (metallic component)
    unsigned int reflect_r = 0, reflect_g = 0, reflect_b = 0;
    traceReflectedRay(hit_point, ray_direction, normal, depth, reflect_r, reflect_g, reflect_b);

    // Tint reflected color by material color (colored metals like gold, copper)
    const float3 tint = make_float3(material_color.x, material_color.y, material_color.z);
    const float tinted_r = static_cast<float>(reflect_r) * tint.x;
    const float tinted_g = static_cast<float>(reflect_g) * tint.y;
    const float tinted_b = static_cast<float>(reflect_b) * tint.z;

    // Compute diffuse component (non-metallic)
    unsigned int diffuse_r = 0, diffuse_g = 0, diffuse_b = 0;
    computeDiffuseColor(hit_point, normal, material_color, diffuse_r, diffuse_g, diffuse_b);

    // Blend: final = metallic * reflection + (1 - metallic) * diffuse
    unsigned int r = static_cast<unsigned int>(fminf(metallic * tinted_r + (1.0f - metallic) * static_cast<float>(diffuse_r), RenderingConstants::COLOR_BYTE_MAX));
    unsigned int g = static_cast<unsigned int>(fminf(metallic * tinted_g + (1.0f - metallic) * static_cast<float>(diffuse_g), RenderingConstants::COLOR_BYTE_MAX));
    unsigned int b = static_cast<unsigned int>(fminf(metallic * tinted_b + (1.0f - metallic) * static_cast<float>(diffuse_b), RenderingConstants::COLOR_BYTE_MAX));

    // Add emission
    addEmissionToColor(r, g, b, material_color, emission);

    optixSetPayload_0(r);
    optixSetPayload_1(g);
    optixSetPayload_2(b);
}

/**
 * Apply Beer-Lambert absorption to refracted light when exiting a medium.
 *
 * STANDARD GRAPHICS ALPHA CONVENTION:
 * alpha=0.0 -> fully transparent (no absorption)
 * alpha=1.0 -> fully opaque (maximum absorption)
 *
 * @param refract_color Refracted ray color (normalized 0.0-1.0)
 * @param distance Distance traveled through the medium
 * @param entering True if ray is entering the medium (no absorption on entry)
 * @param material_color RGBA material color
 * @param distance_scale Optional scale factor for distance (e.g., sphere_scale for spheres)
 * @return Color after Beer-Lambert absorption
 */
__device__ float3 applyBeerLambertAbsorption(
    const float3& refract_color,
    float distance,
    bool entering,
    const float4& material_color,
    float distance_scale = 1.0f
) {
    if (entering) {
        return refract_color;  // No absorption when entering
    }

    const float3 glass_color = make_float3(
        material_color.x,
        material_color.y,
        material_color.z
    );
    const float glass_alpha = material_color.w;

    const float absorption_scale = BEER_LAMBERT_ABSORPTION_SCALE;
    const float3 extinction_constant = make_float3(
        -logf(fmaxf(glass_color.x, COLOR_CHANNEL_MIN_SAFE_VALUE)) * glass_alpha * absorption_scale,
        -logf(fmaxf(glass_color.y, COLOR_CHANNEL_MIN_SAFE_VALUE)) * glass_alpha * absorption_scale,
        -logf(fmaxf(glass_color.z, COLOR_CHANNEL_MIN_SAFE_VALUE)) * glass_alpha * absorption_scale
    );

    const float3 beer_attenuation = make_float3(
        expf(-extinction_constant.x * distance * distance_scale),
        expf(-extinction_constant.y * distance * distance_scale),
        expf(-extinction_constant.z * distance * distance_scale)
    );

    return refract_color * beer_attenuation;
}

//==============================================================================
// Fresnel Color Blending Helper
//==============================================================================

/**
 * Blend reflected and refracted colors using Fresnel coefficient and set payloads.
 *
 * This is the final step of glass/transparent material rendering:
 * 1. Convert integer color payloads to normalized floats
 * 2. Apply Beer-Lambert absorption to refracted color (caller handles this first)
 * 3. Blend colors using Fresnel: final = fresnel * reflect + (1 - fresnel) * refract
 * 4. Add emission if present
 * 5. Convert back to integer and set output payloads
 *
 * @param fresnel Fresnel reflectance coefficient [0, 1]
 * @param reflect_r/g/b Reflected ray color (0-255)
 * @param refract_color Refracted ray color after Beer-Lambert (0.0-1.0 per channel)
 * @param material_color Material color for emission tinting
 * @param emission Emission intensity (0.0-10.0)
 */
__device__ void blendFresnelColorsAndSetPayload(
    float fresnel,
    unsigned int reflect_r,
    unsigned int reflect_g,
    unsigned int reflect_b,
    const float3& refract_color,
    const float4& material_color = make_float4(1.0f, 1.0f, 1.0f, 1.0f),
    float emission = 0.0f
) {
    // Convert reflected color to normalized float
    const float3 reflect_color = make_float3(
        static_cast<float>(reflect_r) / RayTracingConstants::COLOR_BYTE_MAX,
        static_cast<float>(reflect_g) / RayTracingConstants::COLOR_BYTE_MAX,
        static_cast<float>(reflect_b) / RayTracingConstants::COLOR_BYTE_MAX
    );

    // Blend using Fresnel coefficient
    const float3 final_color = make_float3(
        fresnel * reflect_color.x + (1.0f - fresnel) * refract_color.x,
        fresnel * reflect_color.y + (1.0f - fresnel) * refract_color.y,
        fresnel * reflect_color.z + (1.0f - fresnel) * refract_color.z
    );

    // Convert to integer
    unsigned int r = static_cast<unsigned int>(fminf(final_color.x * RayTracingConstants::COLOR_BYTE_MAX, RayTracingConstants::COLOR_BYTE_MAX));
    unsigned int g = static_cast<unsigned int>(fminf(final_color.y * RayTracingConstants::COLOR_BYTE_MAX, RayTracingConstants::COLOR_BYTE_MAX));
    unsigned int b = static_cast<unsigned int>(fminf(final_color.z * RayTracingConstants::COLOR_BYTE_MAX, RayTracingConstants::COLOR_BYTE_MAX));

    // Add emission
    addEmissionToColor(r, g, b, material_color, emission);

    // Set payloads
    optixSetPayload_0(r);
    optixSetPayload_1(g);
    optixSetPayload_2(b);
}

/**
 * Convert integer color payload to normalized float color.
 *
 * @param r/g/b Integer color channels (0-255)
 * @return Normalized float3 color (0.0-1.0 per channel)
 */
__device__ float3 payloadToFloat3(unsigned int r, unsigned int g, unsigned int b) {
    return make_float3(
        static_cast<float>(r) / RayTracingConstants::COLOR_BYTE_MAX,
        static_cast<float>(g) / RayTracingConstants::COLOR_BYTE_MAX,
        static_cast<float>(b) / RayTracingConstants::COLOR_BYTE_MAX
    );
}

//==============================================================================
// Instance Material Helper (IAS Mode)
//==============================================================================

/**
 * Get material properties for the current hit instance.
 *
 * In IAS mode, reads from per-instance material array using optixGetInstanceId().
 * In single-object mode, falls back to global sphere parameters.
 *
 * @param color Output: RGBA color (0-1 range)
 * @param ior Output: Index of refraction
 */
__device__ void getInstanceMaterial(float4& color, float& ior) {
    if (params.use_ias && params.instance_materials) {
        // IAS mode: read from per-instance materials array
        const unsigned int instance_id = optixGetInstanceId();
        const InstanceMaterial& mat = params.instance_materials[instance_id];
        color = make_float4(mat.color[0], mat.color[1], mat.color[2], mat.color[3]);
        ior = mat.ior;
    } else {
        // Single-object mode: use global sphere parameters
        color = make_float4(
            params.sphere_color[0],
            params.sphere_color[1],
            params.sphere_color[2],
            params.sphere_color[3]
        );
        ior = params.sphere_ior;
    }
}

/**
 * Get material properties including PBR values for the current hit instance.
 *
 * In IAS mode, reads from per-instance material array using optixGetInstanceId().
 * In single-object mode, falls back to global sphere parameters with default PBR values.
 *
 * @param color Output: RGBA color (0-1 range)
 * @param ior Output: Index of refraction
 * @param roughness Output: Roughness (0=mirror, 1=diffuse)
 * @param metallic Output: Metallic (0=dielectric, 1=metal)
 * @param specular Output: Specular intensity
 * @param emission Output: Emission intensity (0.0-10.0)
 * @param film_thickness Output: Thin-film thickness in nm (0 = no thin-film)
 */
__device__ void getInstanceMaterialPBR(
    float4& color, float& ior, float& roughness, float& metallic, float& specular, float& emission,
    float& film_thickness
) {
    if (params.use_ias && params.instance_materials) {
        // IAS mode: read from per-instance materials array
        const unsigned int instance_id = optixGetInstanceId();
        const InstanceMaterial& mat = params.instance_materials[instance_id];
        color = make_float4(mat.color[0], mat.color[1], mat.color[2], mat.color[3]);
        ior = mat.ior;
        roughness = mat.roughness;
        metallic = mat.metallic;
        specular = mat.specular;
        emission = mat.emission;
        film_thickness = mat.film_thickness;
    } else {
        // Single-object mode: use global sphere parameters with default PBR values
        color = make_float4(
            params.sphere_color[0],
            params.sphere_color[1],
            params.sphere_color[2],
            params.sphere_color[3]
        );
        ior = params.sphere_ior;
        roughness = MaterialDefaults::DEFAULT_ROUGHNESS;  // Default middle roughness
        metallic = MaterialDefaults::DEFAULT_METALLIC;    // Default non-metallic (dielectric)
        specular = MaterialDefaults::DEFAULT_SPECULAR;    // Default specular intensity
        emission = 0.0f;  // Default no emission
        film_thickness = 0.0f;  // Default no thin-film
    }
}

/**
 * Get texture index for the current hit instance.
 *
 * In IAS mode, reads from per-instance material array using optixGetInstanceId().
 * Returns -1 if no texture is assigned or not in IAS mode.
 *
 * @return Texture index or -1 if no texture
 */
__device__ int getInstanceTextureIndex() {
    if (params.use_ias && params.instance_materials) {
        const unsigned int instance_id = optixGetInstanceId();
        return params.instance_materials[instance_id].texture_index;
    }
    return -1;  // No texture in single-object mode
}

/**
 * Sample texture color for the current instance at given UV coordinates.
 *
 * If the instance has a valid texture, samples it and multiplies with base color.
 * This allows textures to be tinted by the material color.
 *
 * @param base_color The base material color (RGBA)
 * @param uv UV coordinates (0-1 range, will wrap)
 * @return Final color (base_color * texture_color, or just base_color if no texture)
 */
__device__ float4 sampleInstanceTexture(const float4& base_color, const float2& uv) {
    const int tex_index = getInstanceTextureIndex();

    // No texture or invalid index - return base color unchanged
    if (tex_index < 0 || !params.textures || tex_index >= static_cast<int>(params.num_textures)) {
        return base_color;
    }

    // Sample the texture (tex2D returns normalized float4 for cudaReadModeNormalizedFloat)
    const cudaTextureObject_t tex = params.textures[tex_index];
    const float4 tex_color = tex2D<float4>(tex, uv.x, uv.y);

    // Multiply texture color with base color (allows tinting)
    return make_float4(
        base_color.x * tex_color.x,
        base_color.y * tex_color.y,
        base_color.z * tex_color.z,
        base_color.w * tex_color.w
    );
}

//==============================================================================
// Custom Sphere Intersection Program
// From NVIDIA OptiX SDK 9.0 sphere.cu
// Uses normalized direction + length correction for numerical stability
//==============================================================================
//
// DESIGN DECISION: This function (92 lines) intentionally NOT refactored.
//
// This intersection program is adapted from the NVIDIA OptiX SDK and follows
// their established patterns for numerical stability. Extracting helpers would:
// 1. Risk introducing bugs in a well-tested algorithm
// 2. Make it harder to compare with SDK reference implementation
// 3. Provide minimal benefit (the algorithm is inherently monolithic)
//
// The numerical refinement step (lines 533-547 in SDK) requires access to
// intermediate values that would complicate any extracted helper interface.
//==============================================================================
extern "C" __global__ void __intersection__sphere()
{
    const HitGroupData* hit_data = reinterpret_cast<HitGroupData*>(optixGetSbtDataPointer());

    // For IAS mode: Use object-space ray (OptiX transforms ray into object space)
    // For single-object mode: Use world-space ray
    const float3 ray_orig = params.use_ias ? optixGetObjectRayOrigin() : optixGetWorldRayOrigin();
    const float3 ray_dir  = params.use_ias ? optixGetObjectRayDirection() : optixGetWorldRayDirection();
    const float  ray_tmin = optixGetRayTmin();
    const float  ray_tmax = optixGetRayTmax();

    // For IAS mode, use unit sphere at origin (transform matrix handles position/scale)
    // For single-object mode, use sphere parameters from SBT
    float3 center;
    float radius;
    if (params.use_ias) {
        center = make_float3(0.0f, 0.0f, 0.0f);
        radius = 1.0f;
    } else {
        center = make_float3(
            hit_data->sphere_center[0],
            hit_data->sphere_center[1],
            hit_data->sphere_center[2]
        );
        radius = hit_data->sphere_radius;
    }

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
