//==============================================================================
// Triangle Closest Hit Shader
// Handles triangle mesh geometry with interpolated normals
//==============================================================================

/**
 * Handle fully transparent triangle (alpha < threshold).
 * Ray continues through as if triangle doesn't exist.
 */
__device__ void handleFullyTransparentTriangle(
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
        ray_direction,
        CONTINUATION_RAY_OFFSET,
        MAX_RAY_DISTANCE,
        0.0f,
        OptixVisibilityMask(255),
        OPTIX_RAY_FLAG_NONE,
        0, 2, 0,
        continue_r, continue_g, continue_b, next_depth
    );

    optixSetPayload_0(continue_r);
    optixSetPayload_1(continue_g);
    optixSetPayload_2(continue_b);
}

/**
 * Handle fully opaque triangle (alpha >= threshold).
 * Solid surface with diffuse shading.
 */
__device__ void handleFullyOpaqueTriangle(
    const float3& hit_point,
    const float3& normal,
    const float4& color
) {
    const float3 lighting = calculateLighting(hit_point, normal);

    const float3 mesh_color = make_float3(color.x, color.y, color.z);

    const float3 lit_color = make_float3(
        mesh_color.x * lighting.x,
        mesh_color.y * lighting.y,
        mesh_color.z * lighting.z
    );

    const unsigned int r = static_cast<unsigned int>(fminf(lit_color.x * RayTracingConstants::COLOR_BYTE_MAX, RayTracingConstants::COLOR_BYTE_MAX));
    const unsigned int g = static_cast<unsigned int>(fminf(lit_color.y * RayTracingConstants::COLOR_BYTE_MAX, RayTracingConstants::COLOR_BYTE_MAX));
    const unsigned int b = static_cast<unsigned int>(fminf(lit_color.z * RayTracingConstants::COLOR_BYTE_MAX, RayTracingConstants::COLOR_BYTE_MAX));

    optixSetPayload_0(r);
    optixSetPayload_1(g);
    optixSetPayload_2(b);
}

/**
 * Trace final non-recursive ray for triangles when max depth is reached.
 */
__device__ void traceFinalNonRecursiveRayTriangle(
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
    unsigned int final_depth = MAX_TRACE_DEPTH;
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
        0, 2, 0,
        final_r, final_g, final_b, final_depth
    );

    optixSetPayload_0(final_r);
    optixSetPayload_1(final_g);
    optixSetPayload_2(final_b);
}

/**
 * Compute Fresnel reflectance for triangles using Schlick approximation.
 */
__device__ float computeFresnelReflectanceTriangle(
    const float3& ray_direction,
    const float3& normal,
    float ior,
    bool entering
) {
    const float n1 = entering ? 1.0f : ior;
    const float n2 = entering ? ior : 1.0f;
    const float r0 = (n1 - n2) / (n1 + n2);
    const float R0 = r0 * r0;
    const float cos_theta = fabsf(dot(ray_direction, normal));
    const float one_minus_cos = 1.0f - cos_theta;
    return R0 + (1.0f - R0) * one_minus_cos * one_minus_cos * one_minus_cos * one_minus_cos * one_minus_cos;
}

/**
 * Trace reflected ray for triangles and return color components.
 */
__device__ void traceReflectedRayTriangle(
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
        0, 2, 0,
        reflect_r, reflect_g, reflect_b, next_depth
    );
}

/**
 * Trace refracted ray for triangles using Snell's law.
 * Returns false if total internal reflection occurs.
 */
__device__ bool traceRefractedRayTriangle(
    const float3& hit_point,
    const float3& ray_direction,
    const float3& normal,
    float ior,
    bool entering,
    unsigned int depth,
    unsigned int& refract_r,
    unsigned int& refract_g,
    unsigned int& refract_b
) {
    const float n1 = entering ? 1.0f : ior;
    const float n2 = entering ? ior : 1.0f;
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
        0, 2, 0,
        refract_r, refract_g, refract_b, next_depth
    );

    return true;
}

/**
 * Apply Beer-Lambert absorption for triangles when exiting the mesh.
 */
__device__ float3 applyBeerLambertAbsorptionTriangle(
    const float3& refract_color,
    float distance,
    const float4& mesh_color,
    bool entering
) {
    if (entering) {
        return refract_color;  // No absorption when entering
    }

    const float3 glass_color = make_float3(mesh_color.x, mesh_color.y, mesh_color.z);
    const float glass_alpha = mesh_color.w;

    // STANDARD GRAPHICS ALPHA CONVENTION:
    // alpha=0.0 -> fully transparent (no absorption)
    // alpha=1.0 -> fully opaque (maximum absorption)
    const float absorption_scale = BEER_LAMBERT_ABSORPTION_SCALE;
    const float3 extinction_constant = make_float3(
        -logf(fmaxf(glass_color.x, COLOR_CHANNEL_MIN_SAFE_VALUE)) * glass_alpha * absorption_scale,
        -logf(fmaxf(glass_color.y, COLOR_CHANNEL_MIN_SAFE_VALUE)) * glass_alpha * absorption_scale,
        -logf(fmaxf(glass_color.z, COLOR_CHANNEL_MIN_SAFE_VALUE)) * glass_alpha * absorption_scale
    );

    const float3 beer_attenuation = make_float3(
        expf(-extinction_constant.x * distance),
        expf(-extinction_constant.y * distance),
        expf(-extinction_constant.z * distance)
    );

    return refract_color * beer_attenuation;
}

//==============================================================================
// Triangle closest hit shader - handles triangle mesh geometry
//==============================================================================
extern "C" __global__ void __closesthit__triangle() {
    // Get triangle hit group data from SBT
    const TriangleHitGroupData* hit_data = reinterpret_cast<TriangleHitGroupData*>(optixGetSbtDataPointer());

    // Get hit point
    const float t = optixGetRayTmax();
    const float3 ray_origin = optixGetWorldRayOrigin();
    const float3 ray_direction = optixGetWorldRayDirection();
    const float3 hit_point = ray_origin + ray_direction * t;

    // Get triangle primitive index and barycentric coordinates
    const unsigned int prim_idx = optixGetPrimitiveIndex();
    const float2 barycentrics = optixGetTriangleBarycentrics();
    const float u = barycentrics.x;
    const float v = barycentrics.y;
    const float w = 1.0f - u - v;

    // Get vertex indices for this triangle
    const unsigned int idx0 = hit_data->indices[prim_idx * 3 + 0];
    const unsigned int idx1 = hit_data->indices[prim_idx * 3 + 1];
    const unsigned int idx2 = hit_data->indices[prim_idx * 3 + 2];

    // Vertices are interleaved: [px, py, pz, nx, ny, nz] = 6 floats per vertex
    const float* v0 = &hit_data->vertices[idx0 * 6];
    const float* v1 = &hit_data->vertices[idx1 * 6];
    const float* v2 = &hit_data->vertices[idx2 * 6];

    // Interpolate normal using barycentric coordinates
    // Normals are at offset 3 within each vertex
    float3 normal = make_float3(
        w * v0[3] + u * v1[3] + v * v2[3],
        w * v0[4] + u * v1[4] + v * v2[4],
        w * v0[5] + u * v1[5] + v * v2[5]
    );
    normal = normalize(normal);

    // Determine if ray is entering or exiting (front face = entering)
    const bool entering = (dot(ray_direction, normal) < 0.0f);

    // Flip normal to face incoming ray
    if (!entering) {
        normal = make_float3(-normal.x, -normal.y, -normal.z);
    }

    // Get current depth from payload
    const unsigned int depth = optixGetPayload_3();

    // Track depth statistics
    if (params.stats) {
        atomicMax(&params.stats->max_depth_reached, depth + 1);
        atomicMin(&params.stats->min_depth_reached, depth + 1);
    }

    // Get mesh color and alpha
    const float4 mesh_color = make_float4(
        hit_data->color[0],
        hit_data->color[1],
        hit_data->color[2],
        hit_data->color[3]
    );
    const float mesh_alpha = mesh_color.w;
    const float mesh_ior = hit_data->ior;

    // Handle fully transparent triangles
    if (mesh_alpha < ALPHA_FULLY_TRANSPARENT_THRESHOLD) {
        handleFullyTransparentTriangle(hit_point, ray_direction, depth);
        return;
    }

    // Handle fully opaque triangles
    if (mesh_alpha >= ALPHA_FULLY_OPAQUE_THRESHOLD) {
        handleFullyOpaqueTriangle(hit_point, normal, mesh_color);
        return;
    }

    // If max depth reached, trace final non-recursive ray
    if (depth >= MAX_TRACE_DEPTH) {
        traceFinalNonRecursiveRayTriangle(hit_point, ray_direction, normal);
        return;
    }

    // Compute Fresnel reflectance
    const float fresnel = computeFresnelReflectanceTriangle(ray_direction, normal, mesh_ior, entering);

    // Trace reflected ray
    unsigned int reflect_r = 0, reflect_g = 0, reflect_b = 0;
    traceReflectedRayTriangle(hit_point, ray_direction, normal, depth, reflect_r, reflect_g, reflect_b);

    // Trace refracted ray
    unsigned int refract_r = 0, refract_g = 0, refract_b = 0;
    const bool refraction_occurred = traceRefractedRayTriangle(
        hit_point, ray_direction, normal, mesh_ior, entering, depth,
        refract_r, refract_g, refract_b
    );

    // Handle total internal reflection
    if (!refraction_occurred) {
        refract_r = reflect_r;
        refract_g = reflect_g;
        refract_b = reflect_b;
    }

    // Convert refracted color to float for absorption calculation
    float3 refract_color = make_float3(
        static_cast<float>(refract_r) / RayTracingConstants::COLOR_BYTE_MAX,
        static_cast<float>(refract_g) / RayTracingConstants::COLOR_BYTE_MAX,
        static_cast<float>(refract_b) / RayTracingConstants::COLOR_BYTE_MAX
    );

    // Apply Beer-Lambert absorption when exiting
    refract_color = applyBeerLambertAbsorptionTriangle(refract_color, t, mesh_color, entering);

    // Blend reflected and refracted rays using Fresnel coefficient
    const float3 reflect_color = make_float3(
        static_cast<float>(reflect_r) / RayTracingConstants::COLOR_BYTE_MAX,
        static_cast<float>(reflect_g) / RayTracingConstants::COLOR_BYTE_MAX,
        static_cast<float>(reflect_b) / RayTracingConstants::COLOR_BYTE_MAX
    );

    const float3 final_color = make_float3(
        fresnel * reflect_color.x + (1.0f - fresnel) * refract_color.x,
        fresnel * reflect_color.y + (1.0f - fresnel) * refract_color.y,
        fresnel * reflect_color.z + (1.0f - fresnel) * refract_color.z
    );

    optixSetPayload_0(static_cast<unsigned int>(fminf(final_color.x * RayTracingConstants::COLOR_BYTE_MAX, RayTracingConstants::COLOR_BYTE_MAX)));
    optixSetPayload_1(static_cast<unsigned int>(fminf(final_color.y * RayTracingConstants::COLOR_BYTE_MAX, RayTracingConstants::COLOR_BYTE_MAX)));
    optixSetPayload_2(static_cast<unsigned int>(fminf(final_color.z * RayTracingConstants::COLOR_BYTE_MAX, RayTracingConstants::COLOR_BYTE_MAX)));
}

//==============================================================================
// Triangle shadow ray closest hit - marks ray as occluded
//==============================================================================
extern "C" __global__ void __closesthit__triangle_shadow() {
    // Mark ray as occluded (payload_0 = 1 means hit something)
    optixSetPayload_0(1);
}
