#include <optix.h>
#include "../include/OptiXData.h"
#include "../include/VectorMath.h"

extern "C" {
    __constant__ Params params;
}

// Import ray tracing constants from OptiXData.h
using namespace RayTracingConstants;

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

//==============================================================================
// Ray generation shader - generates primary rays from camera
//==============================================================================
extern "C" __global__ void __raygen__rg() {
    // Get ray generation data (camera parameters)
    const RayGenData* raygen_data = reinterpret_cast<RayGenData*>(optixGetSbtDataPointer());

    // Get pixel coordinates
    const uint3 idx = optixGetLaunchIndex();
    const uint3 dim = optixGetLaunchDimensions();

    // Calculate normalized device coordinates [-1, 1]
    // Note: Flip V so that idx.y=0 (top) maps to v=+1 and idx.y=height (bottom) maps to v=-1
    const float u = (static_cast<float>(idx.x) + 0.5f) / static_cast<float>(dim.x) * 2.0f - 1.0f;
    const float v = -((static_cast<float>(idx.y) + 0.5f) / static_cast<float>(dim.y) * 2.0f - 1.0f);

    // Construct ray direction from camera basis vectors
    const float3 ray_origin = make_float3(
        raygen_data->cam_eye[0],
        raygen_data->cam_eye[1],
        raygen_data->cam_eye[2]
    );

    const float3 camera_u = make_float3(raygen_data->camera_u[0], raygen_data->camera_u[1], raygen_data->camera_u[2]);
    const float3 camera_v = make_float3(raygen_data->camera_v[0], raygen_data->camera_v[1], raygen_data->camera_v[2]);
    const float3 camera_w = make_float3(raygen_data->camera_w[0], raygen_data->camera_w[1], raygen_data->camera_w[2]);

    const float3 ray_direction = normalize(camera_u * u + camera_v * v + camera_w);

    // Trace ray
    unsigned int p0, p1, p2, p3;  // Payload for RGB color + depth
    p3 = 0;  // Initial depth = 0
    optixTrace(
        params.handle,                     // Acceleration structure
        ray_origin,                        // Ray origin
        ray_direction,                     // Ray direction
        0.0f,                              // tmin
        MAX_RAY_DISTANCE,       // tmax
        0.0f,                              // rayTime
        OptixVisibilityMask(255),
        OPTIX_RAY_FLAG_NONE,
        0,                       // SBT offset
        1,                       // SBT stride
        0,                       // missSBTIndex
        p0, p1, p2, p3           // Payload (RGB + depth)
    );

    // Convert payload to RGBA
    const unsigned int r = p0;
    const unsigned int g = p1;
    const unsigned int b = p2;

    // Write to output buffer
    const unsigned int pixel_index = idx.y * params.image_width + idx.x;
    params.image[pixel_index * 4 + 0] = static_cast<unsigned char>(r);
    params.image[pixel_index * 4 + 1] = static_cast<unsigned char>(g);
    params.image[pixel_index * 4 + 2] = static_cast<unsigned char>(b);
    params.image[pixel_index * 4 + 3] = 255;  // Alpha
}

//==============================================================================
// Miss shader - returns checkered plane or background color when ray hits nothing
//==============================================================================
extern "C" __global__ void __miss__ms() {
    // Get miss data (background color)
    const MissData* miss_data = reinterpret_cast<MissData*>(optixGetSbtDataPointer());

    // Get ray data
    const float3 ray_origin = optixGetWorldRayOrigin();
    const float3 ray_direction = optixGetWorldRayDirection();

    // Extract plane parameters from params (moved from SBT for performance)
    const int plane_axis = params.plane_axis;         // 0=X, 1=Y, 2=Z
    const float plane_value = params.plane_value;     // Position along axis
    // Note: plane_positive (normal direction) is currently not used - plane is visible from both sides

    // Get ray origin/direction component for the plane axis
    float ray_orig_comp, ray_dir_comp;
    if (plane_axis == 0) {  // X axis
        ray_orig_comp = ray_origin.x;
        ray_dir_comp = ray_direction.x;
    } else if (plane_axis == 1) {  // Y axis
        ray_orig_comp = ray_origin.y;
        ray_dir_comp = ray_direction.y;
    } else {  // Z axis
        ray_orig_comp = ray_origin.z;
        ray_dir_comp = ray_direction.z;
    }

    unsigned int r, g, b;

    // Plane visible from both sides - just check for valid intersection
    // Only compute if ray direction component is non-zero
    if (fabsf(ray_dir_comp) > 1e-6f) {
        const float t = (plane_value - ray_orig_comp) / ray_dir_comp;

        if (t > 0.0f) {  // Intersection is in front of ray origin
            // Compute full intersection point
            const float3 hit_point = ray_origin + ray_direction * t;

            // For checker pattern, use the two components perpendicular to plane axis
            float checker_u, checker_v;
            if (plane_axis == 0) {  // X axis plane (YZ plane)
                checker_u = hit_point.y;
                checker_v = hit_point.z;
            } else if (plane_axis == 1) {  // Y axis plane (XZ plane)
                checker_u = hit_point.x;
                checker_v = hit_point.z;
            } else {  // Z axis plane (XY plane)
                checker_u = hit_point.x;
                checker_v = hit_point.y;
            }

            // Checkered pattern
            const float checker_size = PLANE_CHECKER_SIZE;
            const int check_u = static_cast<int>(floorf(checker_u / checker_size));
            const int check_v = static_cast<int>(floorf(checker_v / checker_size));

            // XOR to create checkerboard pattern
            const bool is_light = ((check_u + check_v) & 1) == 0;

            // Medium gray and very dark gray checker colors
            // These colors are distinct from light spheres (200+) and white (255)
            if (is_light) {
                r = PLANE_CHECKER_LIGHT_GRAY;
                g = PLANE_CHECKER_LIGHT_GRAY;
                b = PLANE_CHECKER_LIGHT_GRAY;
            } else {
                r = PLANE_CHECKER_DARK_GRAY;
                g = PLANE_CHECKER_DARK_GRAY;
                b = PLANE_CHECKER_DARK_GRAY;
            }
        } else {
            // No intersection (t < 0), use background color
            r = static_cast<unsigned int>(miss_data->r * COLOR_SCALE_FACTOR);
            g = static_cast<unsigned int>(miss_data->g * COLOR_SCALE_FACTOR);
            b = static_cast<unsigned int>(miss_data->b * COLOR_SCALE_FACTOR);
        }
    } else {
        // Ray not pointing towards plane, use background color
        r = static_cast<unsigned int>(miss_data->r * COLOR_SCALE_FACTOR);
        g = static_cast<unsigned int>(miss_data->g * COLOR_SCALE_FACTOR);
        b = static_cast<unsigned int>(miss_data->b * COLOR_SCALE_FACTOR);
    }

    // Set payload
    optixSetPayload_0(r);
    optixSetPayload_1(g);
    optixSetPayload_2(b);
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

    // Get sphere alpha from params (moved from SBT for performance)
    const float sphere_alpha = params.sphere_color[3];

    // Handle fully transparent spheres - trace continuation ray through sphere
    if (sphere_alpha < ALPHA_FULLY_TRANSPARENT_THRESHOLD) {
        // Sphere is fully transparent, let ray continue as if sphere doesn't exist
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
            0, 1, 0,
            continue_r, continue_g, continue_b, next_depth
        );

        optixSetPayload_0(continue_r);
        optixSetPayload_1(continue_g);
        optixSetPayload_2(continue_b);
        return;
    }

    // Handle fully opaque spheres (alpha >= threshold) - solid surface with diffuse shading
    if (sphere_alpha >= ALPHA_FULLY_OPAQUE_THRESHOLD) {
        const float3 light_dir_normalized = normalize(make_float3(
            params.light_dir[0],
            params.light_dir[1],
            params.light_dir[2]
        ));
        const float diffuse = fmaxf(0.0f, dot(normal, light_dir_normalized));
        const float lighting = AMBIENT_LIGHT_FACTOR + (1.0f - AMBIENT_LIGHT_FACTOR) * diffuse;

        // Get sphere color from params (RGB only, ignore alpha for solid spheres)
        const float3 sphere_color = make_float3(
            params.sphere_color[0],
            params.sphere_color[1],
            params.sphere_color[2]
        );

        // Apply lighting to solid sphere surface
        const float3 lit_color = make_float3(
            sphere_color.x * lighting,
            sphere_color.y * lighting,
            sphere_color.z * lighting
        );

        const unsigned int r = static_cast<unsigned int>(fminf(lit_color.x * 255.0f, 255.0f));
        const unsigned int g = static_cast<unsigned int>(fminf(lit_color.y * 255.0f, 255.0f));
        const unsigned int b = static_cast<unsigned int>(fminf(lit_color.z * 255.0f, 255.0f));

        optixSetPayload_0(r);
        optixSetPayload_1(g);
        optixSetPayload_2(b);
        return;
    }

    // If max depth reached, trace one final non-recursive ray to get background/plane color
    // This avoids black artifacts from depth cutoff
    if (depth >= MAX_TRACE_DEPTH) {
        // Compute reflection direction for final ray
        const float cos_theta = fabsf(dot(ray_direction, normal));
        const float3 reflect_dir = make_float3(
            ray_direction.x - 2.0f * cos_theta * normal.x,
            ray_direction.y - 2.0f * cos_theta * normal.y,
            ray_direction.z - 2.0f * cos_theta * normal.z
        );

        // Trace one final ray at MAX_TRACE_DEPTH (will not recurse further)
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
            0, 1, 0,
            final_r, final_g, final_b, final_depth
        );

        optixSetPayload_0(final_r);
        optixSetPayload_1(final_g);
        optixSetPayload_2(final_b);
        return;
    }

    // Compute Fresnel reflectance using Schlick approximation
    // Get IOR from params (moved from SBT for performance)
    const float n1 = entering ? 1.0f : params.sphere_ior;
    const float n2 = entering ? params.sphere_ior : 1.0f;
    const float r0 = (n1 - n2) / (n1 + n2);
    const float R0 = r0 * r0;
    const float cos_theta = fabsf(dot(ray_direction, normal));
    const float one_minus_cos = 1.0f - cos_theta;
    const float fresnel = R0 + (1.0f - R0) * one_minus_cos * one_minus_cos * one_minus_cos * one_minus_cos * one_minus_cos;

    // Compute reflection direction: r = d - 2(d·n)n
    const float3 reflect_dir = make_float3(
        ray_direction.x - 2.0f * cos_theta * normal.x,
        ray_direction.y - 2.0f * cos_theta * normal.y,
        ray_direction.z - 2.0f * cos_theta * normal.z
    );

    // Trace reflected ray
    unsigned int reflect_r = 0, reflect_g = 0, reflect_b = 0;
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
        0, 1, 0,
        reflect_r, reflect_g, reflect_b, next_depth
    );

    // Compute refraction with Snell's law
    const float eta = n1 / n2;
    const float k = 1.0f - eta * eta * (1.0f - cos_theta * cos_theta);

    unsigned int refract_r = 0, refract_g = 0, refract_b = 0;
    if (k >= 0.0f) {
        // Refraction possible
        const float coeff = eta * cos_theta - sqrtf(k);
        const float3 refract_dir = make_float3(
            eta * ray_direction.x + coeff * normal.x,
            eta * ray_direction.y + coeff * normal.y,
            eta * ray_direction.z + coeff * normal.z
        );

        const float3 refract_origin = hit_point + refract_dir * CONTINUATION_RAY_OFFSET;
        optixTrace(
            params.handle,
            refract_origin,
            refract_dir,
            CONTINUATION_RAY_OFFSET,
            MAX_RAY_DISTANCE,
            0.0f,
            OptixVisibilityMask(255),
            OPTIX_RAY_FLAG_NONE,
            0, 1, 0,
            refract_r, refract_g, refract_b, next_depth
        );
    } else {
        // Total internal reflection - use reflected ray result for refraction too
        refract_r = reflect_r;
        refract_g = reflect_g;
        refract_b = reflect_b;
    }

    // Apply Beer-Lambert absorption to refracted component (only at EXIT)
    float3 refract_color = make_float3(
        static_cast<float>(refract_r) / 255.0f,
        static_cast<float>(refract_g) / 255.0f,
        static_cast<float>(refract_b) / 255.0f
    );

    if (!entering)  // Exiting - apply absorption to refracted ray
    {
        // Get glass color and alpha from params (moved from SBT for performance)
        const float3 glass_color = make_float3(
            params.sphere_color[0],
            params.sphere_color[1],
            params.sphere_color[2]
        );
        const float glass_alpha = params.sphere_color[3];

        // Calculate extinction constant from color and alpha
        // STANDARD GRAPHICS ALPHA CONVENTION:
        // alpha=0.0 → fully transparent (no absorption)
        // alpha=1.0 → fully opaque (maximum absorption)
        const float absorption_scale = BEER_LAMBERT_ABSORPTION_SCALE;
        const float3 extinction_constant = make_float3(
            -logf(fmaxf(glass_color.x, COLOR_CHANNEL_MIN_SAFE_VALUE)) * glass_alpha * absorption_scale,
            -logf(fmaxf(glass_color.y, COLOR_CHANNEL_MIN_SAFE_VALUE)) * glass_alpha * absorption_scale,
            -logf(fmaxf(glass_color.z, COLOR_CHANNEL_MIN_SAFE_VALUE)) * glass_alpha * absorption_scale
        );

        // Apply Beer-Lambert using t (which varies from ~0.7 at edges to ~1.2 at center)
        // Get scale from params (moved from SBT for performance)
        const float3 beer_attenuation = make_float3(
            expf(-extinction_constant.x * t * params.sphere_scale),
            expf(-extinction_constant.y * t * params.sphere_scale),
            expf(-extinction_constant.z * t * params.sphere_scale)
        );

        // Apply absorption to refracted ray
        refract_color = refract_color * beer_attenuation;
    }

    // Blend reflected and refracted rays using Fresnel coefficient
    const float3 reflect_color = make_float3(
        static_cast<float>(reflect_r) / 255.0f,
        static_cast<float>(reflect_g) / 255.0f,
        static_cast<float>(reflect_b) / 255.0f
    );

    // Final color = fresnel * reflected + (1 - fresnel) * refracted
    const float3 final_color = make_float3(
        fresnel * reflect_color.x + (1.0f - fresnel) * refract_color.x,
        fresnel * reflect_color.y + (1.0f - fresnel) * refract_color.y,
        fresnel * reflect_color.z + (1.0f - fresnel) * refract_color.z
    );

    optixSetPayload_0(static_cast<unsigned int>(fminf(final_color.x * 255.0f, 255.0f)));
    optixSetPayload_1(static_cast<unsigned int>(fminf(final_color.y * 255.0f, 255.0f)));
    optixSetPayload_2(static_cast<unsigned int>(fminf(final_color.z * 255.0f, 255.0f)));
}
