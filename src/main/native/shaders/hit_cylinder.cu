//==============================================================================
// Cylinder Ray Intersection Shader
// Implements ray-cylinder intersection with caps for edge rendering
//==============================================================================
// This file is included by optix_shaders.cu - do not compile separately

//==============================================================================
// Cylinder Intersection Program
//==============================================================================

/**
 * Ray-cylinder intersection using quadratic formula.
 *
 * Cylinder defined by:
 * - p0: start point
 * - p1: end point
 * - radius: cylinder radius
 * - axis: normalized direction from p0 to p1
 *
 * Intersection types:
 * - Body: infinite cylinder surface (quadratic equation)
 * - Caps: disk intersections at p0 and p1
 */
extern "C" __global__ void __intersection__cylinder() {
    // Get cylinder index from instance material's texture_index field
    const unsigned int instanceId = optixGetInstanceId();

    // Bounds check: ensure instanceId is valid
    if (instanceId >= params.num_instances) {
        return;  // Invalid instance ID, skip intersection
    }

    // Null pointer check: ensure instance_materials is valid
    if (!params.instance_materials) {
        return;  // No instance materials, skip intersection
    }

    const InstanceMaterial& mat = params.instance_materials[instanceId];
    const int cylinder_index = mat.texture_index;

    // Bounds check: ensure cylinder_index is valid
    if (cylinder_index < 0 || cylinder_index >= static_cast<int>(params.num_cylinders)) {
        return;  // Invalid cylinder index, skip intersection
    }

    // Null pointer check: ensure cylinder_data is valid
    if (!params.cylinder_data) {
        return;  // No cylinder data, skip intersection
    }

    // Get cylinder data from params buffer
    const CylinderData* cylinder = &params.cylinder_data[cylinder_index];

    // Get ray parameters
    const float3 ray_orig = optixGetWorldRayOrigin();
    const float3 ray_dir = optixGetWorldRayDirection();

    // Cylinder geometry
    const float3 p0 = make_float3(cylinder->p0[0], cylinder->p0[1], cylinder->p0[2]);
    const float3 p1 = make_float3(cylinder->p1[0], cylinder->p1[1], cylinder->p1[2]);
    const float radius = cylinder->radius;

    // Cylinder axis and length
    const float3 axis_vec = make_float3(
        p1.x - p0.x,
        p1.y - p0.y,
        p1.z - p0.z
    );
    const float length = sqrtf(axis_vec.x * axis_vec.x + axis_vec.y * axis_vec.y + axis_vec.z * axis_vec.z);
    const float3 axis = make_float3(
        axis_vec.x / length,
        axis_vec.y / length,
        axis_vec.z / length
    );

    // Ray origin relative to cylinder base
    const float3 oc = make_float3(
        ray_orig.x - p0.x,
        ray_orig.y - p0.y,
        ray_orig.z - p0.z
    );

    // Quadratic equation coefficients for infinite cylinder
    // (ray_orig + t * ray_dir - p0 - s * axis)^2 = radius^2
    // where s is the projection parameter along the axis

    const float rd_dot_axis = ray_dir.x * axis.x + ray_dir.y * axis.y + ray_dir.z * axis.z;
    const float oc_dot_axis = oc.x * axis.x + oc.y * axis.y + oc.z * axis.z;

    const float3 rd_perp = make_float3(
        ray_dir.x - rd_dot_axis * axis.x,
        ray_dir.y - rd_dot_axis * axis.y,
        ray_dir.z - rd_dot_axis * axis.z
    );

    const float3 oc_perp = make_float3(
        oc.x - oc_dot_axis * axis.x,
        oc.y - oc_dot_axis * axis.y,
        oc.z - oc_dot_axis * axis.z
    );

    const float a = rd_perp.x * rd_perp.x + rd_perp.y * rd_perp.y + rd_perp.z * rd_perp.z;
    const float b = 2.0f * (rd_perp.x * oc_perp.x + rd_perp.y * oc_perp.y + rd_perp.z * oc_perp.z);
    const float c = oc_perp.x * oc_perp.x + oc_perp.y * oc_perp.y + oc_perp.z * oc_perp.z - radius * radius;

    const float discriminant = b * b - 4.0f * a * c;

    float t_hit = -1.0f;
    float3 normal;
    unsigned int hit_kind = 0;  // 0 = body, 1 = cap at p0, 2 = cap at p1

    // Check infinite cylinder intersection
    if (discriminant >= 0.0f && fabsf(a) > RayTracingConstants::CYLINDER_QUADRATIC_TOLERANCE) {
        const float sqrt_disc = sqrtf(discriminant);
        const float t1 = (-b - sqrt_disc) / (2.0f * a);
        const float t2 = (-b + sqrt_disc) / (2.0f * a);

        // Check both intersection points
        for (int i = 0; i < 2; i++) {
            const float t = (i == 0) ? t1 : t2;
            if (t > optixGetRayTmin() && t < optixGetRayTmax()) {
                // Check if intersection is within cylinder length
                const float3 hit_point = make_float3(
                    ray_orig.x + t * ray_dir.x,
                    ray_orig.y + t * ray_dir.y,
                    ray_orig.z + t * ray_dir.z
                );
                const float3 hit_vec = make_float3(
                    hit_point.x - p0.x,
                    hit_point.y - p0.y,
                    hit_point.z - p0.z
                );
                const float s = hit_vec.x * axis.x + hit_vec.y * axis.y + hit_vec.z * axis.z;

                if (s >= 0.0f && s <= length) {
                    // Valid body hit
                    if (t_hit < 0.0f || t < t_hit) {
                        t_hit = t;

                        // Calculate body normal (perpendicular to axis)
                        const float3 center_on_axis = make_float3(
                            p0.x + s * axis.x,
                            p0.y + s * axis.y,
                            p0.z + s * axis.z
                        );
                        normal = make_float3(
                            hit_point.x - center_on_axis.x,
                            hit_point.y - center_on_axis.y,
                            hit_point.z - center_on_axis.z
                        );
                        const float norm_len = sqrtf(normal.x * normal.x + normal.y * normal.y + normal.z * normal.z);
                        normal = make_float3(
                            normal.x / norm_len,
                            normal.y / norm_len,
                            normal.z / norm_len
                        );
                        hit_kind = 0;
                    }
                }
            }
        }
    }

    // Check cap intersections (disks at p0 and p1)
    if (fabsf(rd_dot_axis) > RayTracingConstants::CYLINDER_CAP_PARALLEL_THRESHOLD) {
        // Cap at p0
        const float t_p0 = -oc_dot_axis / rd_dot_axis;
        if (t_p0 > optixGetRayTmin() && t_p0 < optixGetRayTmax()) {
            const float3 hit_point = make_float3(
                ray_orig.x + t_p0 * ray_dir.x,
                ray_orig.y + t_p0 * ray_dir.y,
                ray_orig.z + t_p0 * ray_dir.z
            );
            const float3 hit_vec = make_float3(
                hit_point.x - p0.x,
                hit_point.y - p0.y,
                hit_point.z - p0.z
            );
            const float dist_sq = hit_vec.x * hit_vec.x + hit_vec.y * hit_vec.y + hit_vec.z * hit_vec.z;
            if (dist_sq <= radius * radius) {
                if (t_hit < 0.0f || t_p0 < t_hit) {
                    t_hit = t_p0;
                    normal = make_float3(-axis.x, -axis.y, -axis.z);
                    hit_kind = 1;
                }
            }
        }

        // Cap at p1
        const float t_p1 = (length - oc_dot_axis) / rd_dot_axis;
        if (t_p1 > optixGetRayTmin() && t_p1 < optixGetRayTmax()) {
            const float3 hit_point = make_float3(
                ray_orig.x + t_p1 * ray_dir.x,
                ray_orig.y + t_p1 * ray_dir.y,
                ray_orig.z + t_p1 * ray_dir.z
            );
            const float3 hit_vec = make_float3(
                hit_point.x - p1.x,
                hit_point.y - p1.y,
                hit_point.z - p1.z
            );
            const float dist_sq = hit_vec.x * hit_vec.x + hit_vec.y * hit_vec.y + hit_vec.z * hit_vec.z;
            if (dist_sq <= radius * radius) {
                if (t_hit < 0.0f || t_p1 < t_hit) {
                    t_hit = t_p1;
                    normal = make_float3(axis.x, axis.y, axis.z);
                    hit_kind = 2;
                }
            }
        }
    }

    // Report intersection if found
    if (t_hit > 0.0f) {
        // Pack normal into attributes
        optixReportIntersection(
            t_hit,
            hit_kind,
            __float_as_uint(normal.x),
            __float_as_uint(normal.y),
            __float_as_uint(normal.z)
        );
    }
}

//==============================================================================
// Cylinder Closest Hit Program
//==============================================================================

extern "C" __global__ void __closesthit__cylinder() {
    // OPTION B: Single-bounce metallic reflection for depth 0, diffuse fallback for depth > 0
    // This avoids deep recursion while supporting metallic materials on cylinder edges

    // Get hit point and normal
    const float t = optixGetRayTmax();
    const float3 ray_origin = optixGetWorldRayOrigin();
    const float3 ray_direction = optixGetWorldRayDirection();
    const float3 hit_point = make_float3(
        ray_origin.x + ray_direction.x * t,
        ray_origin.y + ray_direction.y * t,
        ray_origin.z + ray_direction.z * t
    );

    const float3 normal = make_float3(
        __uint_as_float(optixGetAttribute_0()),
        __uint_as_float(optixGetAttribute_1()),
        __uint_as_float(optixGetAttribute_2())
    );

    // Get current depth from payload
    const unsigned int depth = optixGetPayload_3();

    // Track depth statistics
    if (params.stats) {
        atomicMax(&params.stats->max_depth_reached, depth + 1);
        atomicMin(&params.stats->min_depth_reached, depth + 1);
    }

    // Get material properties
    float4 material_color;
    float material_ior, roughness, metallic, specular, emission;
    getInstanceMaterialPBR(material_color, material_ior, roughness, metallic, specular, emission);

    // Handle metallic reflection for depth 0 only (single bounce)
    if (depth == 0 && metallic > 0.0f) {
        // Use existing helper function for metallic opaque materials
        // This traces ONE reflected ray, blends with diffuse, and sets payload
        handleMetallicOpaque(hit_point, ray_direction, normal,
                           material_color, metallic, depth, emission);
        return;  // Early exit - handleMetallicOpaque sets payload
    }

    // FALLBACK: Diffuse shading for depth > 0 or non-metallic
    // Use calculateLighting() with skip_shadows=true to avoid recursion issues
    const float3 final_lighting = calculateLighting(hit_point, normal, false, true);

    // Apply to material color
    const float final_r = material_color.x * final_lighting.x * 255.99f;
    const float final_g = material_color.y * final_lighting.y * 255.99f;
    const float final_b = material_color.z * final_lighting.z * 255.99f;

    // Add emission
    const float emissive_r = fminf(final_r + emission * material_color.x * 255.0f, 255.0f);
    const float emissive_g = fminf(final_g + emission * material_color.y * 255.0f, 255.0f);
    const float emissive_b = fminf(final_b + emission * material_color.z * 255.0f, 255.0f);

    optixSetPayload_0(static_cast<unsigned int>(emissive_r));
    optixSetPayload_1(static_cast<unsigned int>(emissive_g));
    optixSetPayload_2(static_cast<unsigned int>(emissive_b));
}

//==============================================================================
// Cylinder Any Hit Program (for transparency)
//==============================================================================

extern "C" __global__ void __anyhit__cylinder() {
    // Get material properties to check alpha
    float4 material_color;
    float material_ior, roughness, metallic, specular, emission;
    getInstanceMaterialPBR(material_color, material_ior, roughness, metallic, specular, emission);

    // If fully transparent, ignore this hit and continue ray
    if (material_color.w < ALPHA_FULLY_TRANSPARENT_THRESHOLD) {
        optixIgnoreIntersection();
    }
}

//==============================================================================
// Cylinder Shadow Programs
//==============================================================================

extern "C" __global__ void __closesthit__cylinder_shadow() {
    // Shadow ray hit - nothing to do, payload already set for blocked
}

extern "C" __global__ void __anyhit__cylinder_shadow() {
    // Get material properties to check alpha
    float4 material_color;
    float material_ior, roughness, metallic, specular, emission;
    getInstanceMaterialPBR(material_color, material_ior, roughness, metallic, specular, emission);

    // If fully transparent, don't block light
    if (material_color.w < ALPHA_FULLY_TRANSPARENT_THRESHOLD) {
        optixIgnoreIntersection();
    }
}
