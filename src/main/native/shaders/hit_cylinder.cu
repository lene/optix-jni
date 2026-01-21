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
    const InstanceMaterial& mat = params.instance_materials[instanceId];
    const int cylinder_index = mat.texture_index;

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
    if (discriminant >= 0.0f && fabsf(a) > 1e-8f) {
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
    if (fabsf(rd_dot_axis) > 1e-8f) {
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
    // Get hit point
    const float t = optixGetRayTmax();
    const float3 ray_origin = optixGetWorldRayOrigin();
    const float3 ray_direction = optixGetWorldRayDirection();
    const float3 hit_point = make_float3(
        ray_origin.x + ray_direction.x * t,
        ray_origin.y + ray_direction.y * t,
        ray_origin.z + ray_direction.z * t
    );

    // Get surface normal from intersection attributes
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
    const float cylinder_alpha = material_color.w;

    // Handle fully transparent cylinders
    if (cylinder_alpha < ALPHA_FULLY_TRANSPARENT_THRESHOLD) {
        handleFullyTransparent(hit_point, ray_direction, depth);
        return;
    }

    // Handle fully opaque cylinders
    if (cylinder_alpha >= ALPHA_FULLY_OPAQUE_THRESHOLD) {
        if (metallic > 0.0f) {
            handleMetallicOpaque(hit_point, ray_direction, normal, material_color, metallic, depth, emission);
            return;
        } else {
            handleFullyOpaque(hit_point, normal, material_color, emission);
            return;
        }
    }

    // Handle semi-transparent cylinders (glass-like)
    if (depth >= MAX_TRACE_DEPTH) {
        traceFinalNonRecursiveRay(hit_point, ray_direction, normal);
        return;
    }

    // Determine if ray is entering or exiting (for refraction)
    const float dot_normal = ray_direction.x * normal.x + ray_direction.y * normal.y + ray_direction.z * normal.z;
    const bool entering = dot_normal < 0.0f;

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

    // Convert refracted color to float for absorption
    float3 refract_color = payloadToFloat3(refract_r, refract_g, refract_b);

    // Apply Beer-Lambert absorption (use cylinder length as distance scale)
    refract_color = applyBeerLambertAbsorption(refract_color, t, entering, material_color, 1.0f);

    // Blend reflected and refracted colors using Fresnel
    blendFresnelColorsAndSetPayload(fresnel, reflect_r, reflect_g, reflect_b, refract_color, material_color, emission);
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
