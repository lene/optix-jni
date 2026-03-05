//==============================================================================
// Triangle Closest Hit Shader
// Handles triangle mesh geometry with interpolated normals
//==============================================================================

/**
 * Interpolated triangle geometry data from hit point.
 */
struct TriangleGeometry {
    float3 hit_point;
    float3 normal;       // Face-aligned normal (points toward ray origin)
    float2 uv_coords;
    float t;             // Ray parameter at hit
    bool entering;       // True if ray is entering the mesh (front face hit)
    float vertex_alpha;  // Interpolated per-vertex alpha (1.0 if not present)
};

/**
 * Get interpolated geometry data from triangle hit.
 *
 * Extracts hit point, interpolated normal, and UV coordinates using
 * barycentric interpolation from the triangle's vertex attributes.
 *
 * @param hit_data Triangle hit group data from SBT
 * @return Interpolated geometry at hit point
 */
__device__ TriangleGeometry getTriangleGeometry(const TriangleHitGroupData* hit_data) {
    TriangleGeometry geom;

    // Get hit point
    geom.t = optixGetRayTmax();
    const float3 ray_origin = optixGetWorldRayOrigin();
    const float3 ray_direction = optixGetWorldRayDirection();
    geom.hit_point = ray_origin + ray_direction * geom.t;

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

    // Get vertex stride (6 for pos+normal, 8 for pos+normal+uv)
    const unsigned int stride = hit_data->vertex_stride;

    // Vertices are interleaved: [px, py, pz, nx, ny, nz, (u, v)] = stride floats per vertex
    const float* v0 = &hit_data->vertices[idx0 * stride];
    const float* v1 = &hit_data->vertices[idx1 * stride];
    const float* v2 = &hit_data->vertices[idx2 * stride];

    // Interpolate normal using barycentric coordinates
    // Normals are at offset 3 within each vertex
    float3 normal = make_float3(
        w * v0[3] + u * v1[3] + v * v2[3],
        w * v0[4] + u * v1[4] + v * v2[4],
        w * v0[5] + u * v1[5] + v * v2[5]
    );
    normal = normalize(normal);

    // Interpolate UV coordinates if available (stride >= 8)
    geom.uv_coords = make_float2(0.0f, 0.0f);
    if (stride >= VERTEX_STRIDE_WITH_UV) {
        // UVs are at offset 6 within each vertex
        geom.uv_coords = make_float2(
            w * v0[6] + u * v1[6] + v * v2[6],
            w * v0[7] + u * v1[7] + v * v2[7]
        );
    }

    // Interpolate per-vertex alpha if available (stride >= 9)
    // Alpha is at offset 8 within each vertex
    // Default to 1.0 (fully opaque) if not present
    float vertex_alpha = 1.0f;
    if (stride >= VERTEX_STRIDE_WITH_ALPHA) {
        vertex_alpha = w * v0[8] + u * v1[8] + v * v2[8];
    }
    // Store in a global variable that material function can access
    // Note: We'll pass this through the material function parameter
    geom.vertex_alpha = vertex_alpha;

    // Determine if ray is entering or exiting (front face = entering)
    geom.entering = (dot(ray_direction, normal) < 0.0f);

    // Flip normal to face incoming ray
    geom.normal = geom.entering ? normal : make_float3(-normal.x, -normal.y, -normal.z);

    return geom;
}

/**
 * Get material properties for triangle mesh including PBR values.
 *
 * In IAS mode, reads from per-instance materials array.
 * In single-object mode, uses SBT hit group data.
 * Applies texture sampling if available.
 *
 * @param hit_data Triangle hit group data from SBT
 * @param uv_coords UV coordinates for texture sampling
 * @param vertex_stride Vertex stride (determines UV availability)
 * @param vertex_alpha Per-vertex alpha from interpolation (1.0 if not present)
 * @param color Output: RGBA material color (alpha multiplied with vertex_alpha)
 * @param ior Output: Index of refraction
 * @param roughness Output: Roughness (0=mirror, 1=diffuse)
 * @param metallic Output: Metallic (0=dielectric, 1=metal)
 * @param specular Output: Specular intensity
 * @param out_emission Output: Emission intensity (0.0-10.0)
 */
__device__ void getTriangleMaterial(
    const TriangleHitGroupData* hit_data,
    const float2& uv_coords,
    unsigned int vertex_stride,
    float vertex_alpha,
    float4& color,
    float& ior,
    float& roughness,
    float& metallic,
    float& specular,
    float& film_thickness,
    float& out_emission         // NEW
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
        film_thickness = mat.film_thickness;
        out_emission = mat.emission;

        // Apply texture if available (in IAS mode only)
        if (vertex_stride >= VERTEX_STRIDE_WITH_UV) {
            color = sampleInstanceTexture(color, uv_coords);
        }
    } else {
        // Single-object mode: use SBT hit group data
        color = make_float4(
            hit_data->color[0],
            hit_data->color[1],
            hit_data->color[2],
            hit_data->color[3]
        );
        ior = hit_data->ior;
        // Default PBR values for single-object mode
        roughness = MaterialDefaults::DEFAULT_ROUGHNESS;
        metallic = MaterialDefaults::DEFAULT_METALLIC;
        specular = MaterialDefaults::DEFAULT_SPECULAR;
        film_thickness = 0.0f;
        out_emission = 0.0f;
    }

    // Multiply material alpha with per-vertex alpha (for fractional level rendering)
    // vertex_alpha = 1.0 for vertices without alpha channel (stride < 9)
    // For fractional levels: level N has vertex_alpha < 1.0, level N+1 has vertex_alpha = 1.0
    color.w *= vertex_alpha;
}

//==============================================================================
// Triangle closest hit shader - handles triangle mesh geometry
//==============================================================================
extern "C" __global__ void __closesthit__triangle() {
    // Get triangle hit group data from SBT
    const TriangleHitGroupData* hit_data = reinterpret_cast<TriangleHitGroupData*>(optixGetSbtDataPointer());

    // Get interpolated geometry (hit point, normal, UVs)
    const TriangleGeometry geom = getTriangleGeometry(hit_data);
    const float3 ray_direction = optixGetWorldRayDirection();

    // Get current depth from payload
    const unsigned int depth = optixGetPayload_3();

    // Track depth statistics
    if (params.stats) {
        atomicMax(&params.stats->max_depth_reached, depth + 1);
        atomicMin(&params.stats->min_depth_reached, depth + 1);
    }

    // Get material properties including PBR values (color, IOR, roughness, metallic, specular, film_thickness, emission)
    float4 mesh_color;
    float mesh_ior, roughness, metallic, specular, film_thickness, mesh_emission;
    getTriangleMaterial(hit_data, geom.uv_coords, hit_data->vertex_stride, geom.vertex_alpha,
                       mesh_color, mesh_ior, roughness, metallic, specular, film_thickness, mesh_emission);

    const float mesh_alpha = mesh_color.w;

    // Handle fully transparent triangles
    if (mesh_alpha < ALPHA_FULLY_TRANSPARENT_THRESHOLD) {
        handleFullyTransparent(geom.hit_point, ray_direction, depth);
        return;
    }

    // Handle fully opaque triangles with metallic/diffuse blending
    if (mesh_alpha >= ALPHA_FULLY_OPAQUE_THRESHOLD) {
        // Check if material has any metallic component
        if (metallic > 0.0f) {
            handleMetallicOpaque(geom.hit_point, ray_direction, geom.normal, mesh_color, metallic, depth);
            return;
        } else {
            // Fully non-metallic, just diffuse shading
            handleFullyOpaque(geom.hit_point, geom.normal, mesh_color);
            return;
        }
    }

    // Handle coverage alpha (fractional transparency rendering).
    // Two cases use this diffuse-blend path:
    //
    // 1. Per-vertex alpha channel in mesh (stride >= 9): merged fractional meshes for
    //    sponge-volume, sponge-surface, and tesseract-sponge. Blend factor = vertex_alpha.
    //
    // 2. IAS mode with fractional material alpha: cube-sponge ghost instances whose base
    //    cube mesh has stride=6 (no per-vertex alpha channel). The ghost alpha is stored in
    //    the per-instance material color.w. Blend factor = mesh_alpha.
    //
    // Without this path, fractional-alpha surfaces fall through to the Fresnel/refraction
    // path. At IOR=1.0 (matte material) that path produces Fresnel=0, so the ghost cubes
    // appear fully transparent regardless of the material alpha value — which is wrong.
    const bool has_vertex_alpha_channel = hit_data->vertex_stride >= VERTEX_STRIDE_WITH_ALPHA;
    const float coverage_alpha = has_vertex_alpha_channel ? geom.vertex_alpha : mesh_alpha;
    const bool use_coverage_blend =
        (has_vertex_alpha_channel && geom.vertex_alpha < ALPHA_FULLY_OPAQUE_THRESHOLD) ||
        (!has_vertex_alpha_channel && params.use_ias && mesh_alpha < ALPHA_FULLY_OPAQUE_THRESHOLD);

    if (use_coverage_blend) {
        if (depth >= MAX_TRACE_DEPTH) {
            // At max depth, just render as opaque to avoid black cutoff
            handleFullyOpaque(geom.hit_point, geom.normal, mesh_color);
            return;
        }

        // Compute diffuse shading for this face (as if fully opaque)
        unsigned int diffuse_r = 0, diffuse_g = 0, diffuse_b = 0;
        computeDiffuseColor(geom.hit_point, geom.normal, mesh_color, diffuse_r, diffuse_g, diffuse_b);

        // Trace continuation ray through the face to get what lies behind
        unsigned int through_r = 0, through_g = 0, through_b = 0;
        traceContinuationRay(geom.hit_point, ray_direction, depth, through_r, through_g, through_b);

        // Coverage blend: coverage_alpha * diffuse + (1 - coverage_alpha) * through
        const float a = coverage_alpha;
        const unsigned int r = static_cast<unsigned int>(fminf(a * static_cast<float>(diffuse_r) + (1.0f - a) * static_cast<float>(through_r), RayTracingConstants::COLOR_BYTE_MAX));
        const unsigned int g = static_cast<unsigned int>(fminf(a * static_cast<float>(diffuse_g) + (1.0f - a) * static_cast<float>(through_g), RayTracingConstants::COLOR_BYTE_MAX));
        const unsigned int b = static_cast<unsigned int>(fminf(a * static_cast<float>(diffuse_b) + (1.0f - a) * static_cast<float>(through_b), RayTracingConstants::COLOR_BYTE_MAX));

        optixSetPayload_0(r);
        optixSetPayload_1(g);
        optixSetPayload_2(b);
        return;
    }

    // If max depth reached, trace final non-recursive ray
    if (depth >= MAX_TRACE_DEPTH) {
        traceFinalNonRecursiveRay(geom.hit_point, ray_direction, geom.normal);
        return;
    }

    // Trace reflected ray
    unsigned int reflect_r = 0, reflect_g = 0, reflect_b = 0;
    traceReflectedRay(geom.hit_point, ray_direction, geom.normal, depth, reflect_r, reflect_g, reflect_b);

    // Trace refracted ray
    unsigned int refract_r = 0, refract_g = 0, refract_b = 0;
    const bool refraction_occurred = traceRefractedRay(
        geom.hit_point, ray_direction, geom.normal, geom.entering, depth, mesh_ior,
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

    // Apply Beer-Lambert absorption when exiting (triangles use default distance_scale=1.0)
    refract_color = applyBeerLambertAbsorption(refract_color, geom.t, geom.entering, mesh_color);

    // Compute Fresnel reflectance (RGB for thin-film, scalar for standard)
    if (film_thickness > 0.0f) {
        const float cos_theta = fabsf(dot(ray_direction, geom.normal));
        const float3 fresnel_rgb = computeThinFilmReflectance(cos_theta, mesh_ior, film_thickness);
        blendFresnelColorsRGBAndSetPayload(fresnel_rgb, reflect_r, reflect_g, reflect_b, refract_color, mesh_color, mesh_emission);
    } else {
        const float fresnel = computeFresnelReflectance(ray_direction, geom.normal, geom.entering, mesh_ior);
        blendFresnelColorsAndSetPayload(fresnel, reflect_r, reflect_g, reflect_b, refract_color, mesh_color, mesh_emission);
    }
}

//==============================================================================
// Triangle shadow ray closest hit - marks ray as occluded
//==============================================================================
extern "C" __global__ void __closesthit__triangle_shadow() {
    // Mark ray as occluded (payload_0 = 1 means hit something)
    optixSetPayload_0(1);
}
