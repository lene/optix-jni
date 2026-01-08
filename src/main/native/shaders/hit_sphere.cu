//==============================================================================
// Helper Functions for Closest Hit Shader
//==============================================================================

/**
 * Handle fully transparent sphere (alpha < threshold).
 * Ray continues through as if sphere doesn't exist.
 */
__device__ void handleFullyTransparentSphere(
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
        0, 2, 0,  // ray_type=0 (primary), stride=2, miss_index=0
        continue_r, continue_g, continue_b, next_depth
    );

    optixSetPayload_0(continue_r);
    optixSetPayload_1(continue_g);
    optixSetPayload_2(continue_b);
}

/**
 * Handle fully opaque sphere (alpha >= threshold).
 * Solid surface with diffuse shading.
 */
__device__ void handleFullyOpaqueSphere(
    const float3& hit_point,
    const float3& normal,
    const float4& material_color
) {
    const float3 lighting = calculateLighting(hit_point, normal);

    const float3 sphere_color = make_float3(
        material_color.x,
        material_color.y,
        material_color.z
    );

    const float3 lit_color = make_float3(
        sphere_color.x * lighting.x,
        sphere_color.y * lighting.y,
        sphere_color.z * lighting.z
    );

    const unsigned int r = static_cast<unsigned int>(fminf(lit_color.x * RayTracingConstants::COLOR_BYTE_MAX, RayTracingConstants::COLOR_BYTE_MAX));
    const unsigned int g = static_cast<unsigned int>(fminf(lit_color.y * RayTracingConstants::COLOR_BYTE_MAX, RayTracingConstants::COLOR_BYTE_MAX));
    const unsigned int b = static_cast<unsigned int>(fminf(lit_color.z * RayTracingConstants::COLOR_BYTE_MAX, RayTracingConstants::COLOR_BYTE_MAX));

    optixSetPayload_0(r);
    optixSetPayload_1(g);
    optixSetPayload_2(b);
}

/**
 * Trace final non-recursive ray when max depth is reached.
 * Avoids black artifacts from depth cutoff.
 */
__device__ void traceFinalNonRecursiveRay(
    const float3& hit_point,
    const float3& ray_direction,
    const float3& normal
) {
    const float cos_theta = fabsf(dot(ray_direction, normal));
    const float3 reflect_dir = make_float3(
        ray_direction.x - 2.0f * cos_theta * normal.x,
        ray_direction.y - 2.0f * cos_theta * normal.y,
        ray_direction.z - 2.0f * cos_theta * normal.z
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
        0, 2, 0,  // ray_type=0 (primary), stride=2, miss_index=0
        final_r, final_g, final_b, final_depth
    );

    optixSetPayload_0(final_r);
    optixSetPayload_1(final_g);
    optixSetPayload_2(final_b);
}

/**
 * Compute Fresnel reflectance using Schlick approximation.
 */
__device__ float computeFresnelReflectance(
    const float3& ray_direction,
    const float3& normal,
    bool entering,
    float material_ior
) {
    const float n1 = entering ? 1.0f : material_ior;
    const float n2 = entering ? material_ior : 1.0f;
    const float r0 = (n1 - n2) / (n1 + n2);
    const float R0 = r0 * r0;
    const float cos_theta = fabsf(dot(ray_direction, normal));
    const float one_minus_cos = 1.0f - cos_theta;
    return R0 + (1.0f - R0) * one_minus_cos * one_minus_cos * one_minus_cos * one_minus_cos * one_minus_cos;
}

/**
 * Trace reflected ray and return color components.
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
    const float cos_theta = fabsf(dot(ray_direction, normal));
    const float3 reflect_dir = make_float3(
        ray_direction.x - 2.0f * cos_theta * normal.x,
        ray_direction.y - 2.0f * cos_theta * normal.y,
        ray_direction.z - 2.0f * cos_theta * normal.z
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
        0, 2, 0,  // ray_type=0 (primary), stride=2, miss_index=0
        reflect_r, reflect_g, reflect_b, next_depth
    );
}

/**
 * Trace refracted ray using Snell's law.
 * Returns false if total internal reflection occurs.
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
        0, 2, 0,  // ray_type=0 (primary), stride=2, miss_index=0
        refract_r, refract_g, refract_b, next_depth
    );

    return true;
}

/**
 * Apply Beer-Lambert absorption to refracted light when exiting the sphere.
 */
__device__ float3 applyBeerLambertAbsorption(
    const float3& refract_color,
    float distance,
    bool entering,
    const float4& material_color
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

    // STANDARD GRAPHICS ALPHA CONVENTION:
    // alpha=0.0 → fully transparent (no absorption)
    // alpha=1.0 → fully opaque (maximum absorption)
    const float absorption_scale = BEER_LAMBERT_ABSORPTION_SCALE;
    const float3 extinction_constant = make_float3(
        -logf(fmaxf(glass_color.x, COLOR_CHANNEL_MIN_SAFE_VALUE)) * glass_alpha * absorption_scale,
        -logf(fmaxf(glass_color.y, COLOR_CHANNEL_MIN_SAFE_VALUE)) * glass_alpha * absorption_scale,
        -logf(fmaxf(glass_color.z, COLOR_CHANNEL_MIN_SAFE_VALUE)) * glass_alpha * absorption_scale
    );

    const float3 beer_attenuation = make_float3(
        expf(-extinction_constant.x * distance * params.sphere_scale),
        expf(-extinction_constant.y * distance * params.sphere_scale),
        expf(-extinction_constant.z * distance * params.sphere_scale)
    );

    return refract_color * beer_attenuation;
}

//==============================================================================
// Closest hit shader - computes Fresnel reflection and Snell's law refraction
//==============================================================================
extern "C" __global__ void __closesthit__ch() {
    // Get hit group data
    const HitGroupData* hit_data = reinterpret_cast<HitGroupData*>(optixGetSbtDataPointer());

    // Get hit point (ray origin + t * ray direction)
    const float t = optixGetRayTmax();
    const float3 ray_origin = optixGetWorldRayOrigin();
    const float3 ray_direction = optixGetWorldRayDirection();
    const float3 hit_point = ray_origin + ray_direction * t;

    // Get hit type from custom intersection program
    const unsigned int hit_kind = optixGetHitKind();
    const bool entering = (hit_kind == 0);  // 0=entry, 1=exit

    // Get surface normal from intersection attributes
    const float3 outward_normal = make_float3(
        __uint_as_float(optixGetAttribute_0()),
        __uint_as_float(optixGetAttribute_1()),
        __uint_as_float(optixGetAttribute_2())
    );

    // Surface normal (points toward incoming ray)
    const float3 normal = entering ? outward_normal : make_float3(-outward_normal.x, -outward_normal.y, -outward_normal.z);

    // Get current depth from payload
    const unsigned int depth = optixGetPayload_3();

    // Track depth statistics
    if (params.stats) {
        atomicMax(&params.stats->max_depth_reached, depth + 1);
        atomicMin(&params.stats->min_depth_reached, depth + 1);
    }

    // Get material properties (from IAS instance or global params)
    float4 material_color;
    float material_ior;
    getInstanceMaterial(material_color, material_ior);
    const float sphere_alpha = material_color.w;

    // Handle fully transparent spheres
    if (sphere_alpha < ALPHA_FULLY_TRANSPARENT_THRESHOLD) {
        handleFullyTransparentSphere(hit_point, ray_direction, depth);
        return;
    }

    // Handle fully opaque spheres
    if (sphere_alpha >= ALPHA_FULLY_OPAQUE_THRESHOLD) {
        handleFullyOpaqueSphere(hit_point, normal, material_color);
        return;
    }

    // If max depth reached, trace final non-recursive ray
    if (depth >= MAX_TRACE_DEPTH) {
        traceFinalNonRecursiveRay(hit_point, ray_direction, normal);
        return;
    }

    // Compute Fresnel reflectance
    const float fresnel = computeFresnelReflectance(ray_direction, normal, entering, material_ior);

    // Trace reflected ray
    unsigned int reflect_r = 0, reflect_g = 0, reflect_b = 0;
    traceReflectedRay(hit_point, ray_direction, normal, depth, reflect_r, reflect_g, reflect_b);

    // Trace refracted ray
    unsigned int refract_r = 0, refract_g = 0, refract_b = 0;
    const bool refraction_occurred = traceRefractedRay(
        hit_point, ray_direction, normal, entering, depth, material_ior,
        refract_r, refract_g, refract_b
    );

    // Handle total internal reflection
    if (!refraction_occurred) {
        refract_r = reflect_r;
        refract_g = reflect_g;
        refract_b = reflect_b;
    }

    // Convert refracted color to float for absorption calculation
    float3 refract_color = payloadToFloat3(refract_r, refract_g, refract_b);

    // Apply Beer-Lambert absorption when exiting
    refract_color = applyBeerLambertAbsorption(refract_color, t, entering, material_color);

    // Blend reflected and refracted colors using Fresnel and set output payloads
    blendFresnelColorsAndSetPayload(fresnel, reflect_r, reflect_g, reflect_b, refract_color);
}
