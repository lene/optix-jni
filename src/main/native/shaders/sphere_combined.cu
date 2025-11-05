#include <optix.h>
#include "../include/OptiXData.h"

extern "C" {
    __constant__ Params params;
}

// Constants for ray tracing
namespace Constants {
    constexpr float MAX_RAY_DISTANCE = 1e16f;
    constexpr float COLOR_SCALE_FACTOR = 255.99f;  // Slightly less than 256 to avoid overflow
    constexpr float CONTINUATION_RAY_OFFSET = 0.001f;  // Small offset to avoid self-intersection
}

// Device-side vector math helper functions
__device__ inline float3 normalize(float3 v) {
    const float len = sqrtf(v.x * v.x + v.y * v.y + v.z * v.z);
    return make_float3(v.x / len, v.y / len, v.z / len);
}

__device__ inline float dot(float3 a, float3 b) {
    return a.x * b.x + a.y * b.y + a.z * b.z;
}

__device__ inline float3 operator+(float3 a, float3 b) {
    return make_float3(a.x + b.x, a.y + b.y, a.z + b.z);
}

__device__ inline float3 operator*(float3 v, float s) {
    return make_float3(v.x * s, v.y * s, v.z * s);
}

//==============================================================================
// Custom Sphere Intersection Program
//==============================================================================
extern "C" __global__ void __intersection__sphere()
{
    // Get hit group data containing sphere parameters
    const HitGroupData* hit_data = reinterpret_cast<HitGroupData*>(optixGetSbtDataPointer());

    // Extract sphere geometry
    const float3 center = make_float3(
        hit_data->sphere_center[0],
        hit_data->sphere_center[1],
        hit_data->sphere_center[2]
    );
    const float radius = hit_data->sphere_radius;

    // Get ray parameters
    const float3 ray_orig = optixGetWorldRayOrigin();
    const float3 ray_dir = optixGetWorldRayDirection();
    const float ray_tmin = optixGetRayTmin();
    const float ray_tmax = optixGetRayTmax();

    // Ray-sphere intersection using quadratic formula
    // Sphere equation: |P - C|² = r²
    // Ray equation: P = O + tD
    // Substituting: |O + tD - C|² = r²
    // Expanding: t²(D·D) + 2t(D·(O-C)) + (O-C)·(O-C) - r² = 0

    const float3 O = make_float3(
        ray_orig.x - center.x,
        ray_orig.y - center.y,
        ray_orig.z - center.z
    );
    const float a = dot(ray_dir, ray_dir);  // Should be 1 if normalized
    const float b = 2.0f * dot(O, ray_dir);
    const float c = dot(O, O) - radius * radius;

    const float discriminant = b * b - 4.0f * a * c;

    if (discriminant < 0.0f) {
        // No intersection
        return;
    }

    const float sqrt_disc = sqrtf(discriminant);
    const float inv_2a = 0.5f / a;

    // Calculate both intersection points
    const float t1 = (-b - sqrt_disc) * inv_2a;  // Near intersection
    const float t2 = (-b + sqrt_disc) * inv_2a;  // Far intersection

    // Determine ray origin position relative to sphere
    const float origin_dist_sq = dot(O, O);
    const float radius_sq = radius * radius;
    const bool origin_outside = (origin_dist_sq > radius_sq + 0.001f);

    // Report valid intersections
    if (t1 >= ray_tmin && t1 <= ray_tmax) {
        // Near intersection (entry from outside OR exit from inside)
        const float3 hit_point = make_float3(
            ray_orig.x + t1 * ray_dir.x,
            ray_orig.y + t1 * ray_dir.y,
            ray_orig.z + t1 * ray_dir.z
        );
        const float3 normal = make_float3(
            (hit_point.x - center.x) / radius,
            (hit_point.y - center.y) / radius,
            (hit_point.z - center.z) / radius
        );

        // Determine hit type: 0=entry, 1=exit
        const unsigned int hit_kind = origin_outside ? 0 : 1;

        // Report intersection with normal as attributes
        optixReportIntersection(
            t1,
            hit_kind,
            __float_as_uint(normal.x),
            __float_as_uint(normal.y),
            __float_as_uint(normal.z)
        );
    }

    if (t2 >= ray_tmin && t2 <= ray_tmax && t2 != t1) {
        // Far intersection (typically exit)
        const float3 hit_point = make_float3(
            ray_orig.x + t2 * ray_dir.x,
            ray_orig.y + t2 * ray_dir.y,
            ray_orig.z + t2 * ray_dir.z
        );
        const float3 normal = make_float3(
            (hit_point.x - center.x) / radius,
            (hit_point.y - center.y) / radius,
            (hit_point.z - center.z) / radius
        );

        // Far intersection is typically an exit (hit_kind=1)
        const unsigned int hit_kind = 1;

        optixReportIntersection(
            t2,
            hit_kind,
            __float_as_uint(normal.x),
            __float_as_uint(normal.y),
            __float_as_uint(normal.z)
        );
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
    const float u = (static_cast<float>(idx.x) + 0.5f) / static_cast<float>(dim.x) * 2.0f - 1.0f;
    const float v = (static_cast<float>(idx.y) + 0.5f) / static_cast<float>(dim.y) * 2.0f - 1.0f;

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
    unsigned int p0, p1, p2;  // Payload for RGB color
    optixTrace(
        params.handle,                     // Acceleration structure
        ray_origin,                        // Ray origin
        ray_direction,                     // Ray direction
        0.0f,                              // tmin
        Constants::MAX_RAY_DISTANCE,       // tmax
        0.0f,                              // rayTime
        OptixVisibilityMask(255),
        OPTIX_RAY_FLAG_NONE,
        0,                       // SBT offset
        1,                       // SBT stride
        0,                       // missSBTIndex
        p0, p1, p2               // Payload
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

    // Checkered plane at z=-10
    const float plane_z = -10.0f;

    // Ray-plane intersection: origin.z + t * direction.z = plane_z
    // Solve for t: t = (plane_z - origin.z) / direction.z
    unsigned int r, g, b;

    if (ray_direction.z < 0.0f) {  // Ray pointing towards plane (negative z)
        const float t = (plane_z - ray_origin.z) / ray_direction.z;

        if (t > 0.0f) {  // Intersection is in front of ray origin
            // Compute intersection point
            const float hit_x = ray_origin.x + t * ray_direction.x;
            const float hit_y = ray_origin.y + t * ray_direction.y;

            // Checkered pattern with 0.5 unit squares
            const float checker_size = 0.5f;
            const int check_x = static_cast<int>(floorf(hit_x / checker_size));
            const int check_y = static_cast<int>(floorf(hit_y / checker_size));

            // XOR to create checkerboard pattern
            const bool is_light = ((check_x + check_y) & 1) == 0;

            // Light gray (240, 240, 240) and dark gray (40, 40, 40)
            if (is_light) {
                r = 240;
                g = 240;
                b = 240;
            } else {
                r = 40;
                g = 40;
                b = 40;
            }
        } else {
            // No intersection (t < 0), use background color
            r = static_cast<unsigned int>(miss_data->r * Constants::COLOR_SCALE_FACTOR);
            g = static_cast<unsigned int>(miss_data->g * Constants::COLOR_SCALE_FACTOR);
            b = static_cast<unsigned int>(miss_data->b * Constants::COLOR_SCALE_FACTOR);
        }
    } else {
        // Ray not pointing towards plane, use background color
        r = static_cast<unsigned int>(miss_data->r * Constants::COLOR_SCALE_FACTOR);
        g = static_cast<unsigned int>(miss_data->g * Constants::COLOR_SCALE_FACTOR);
        b = static_cast<unsigned int>(miss_data->b * Constants::COLOR_SCALE_FACTOR);
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

    // Compute Fresnel reflectance using Schlick approximation
    const float n1 = entering ? 1.0f : hit_data->ior;  // Air or glass
    const float n2 = entering ? hit_data->ior : 1.0f;  // Glass or air
    const float r0 = (n1 - n2) / (n1 + n2);
    const float R0 = r0 * r0;
    const float cos_theta = fabsf(dot(ray_direction, normal));
    const float one_minus_cos = 1.0f - cos_theta;
    const float fresnel = R0 + (1.0f - R0) * one_minus_cos * one_minus_cos * one_minus_cos * one_minus_cos * one_minus_cos;

    // Compute reflected ray direction: r = d - 2(d·n)n
    const float dot_dn = dot(ray_direction, normal);
    const float3 reflect_dir = make_float3(
        ray_direction.x - 2.0f * dot_dn * normal.x,
        ray_direction.y - 2.0f * dot_dn * normal.y,
        ray_direction.z - 2.0f * dot_dn * normal.z
    );

    // Cast reflected ray
    unsigned int reflect_r, reflect_g, reflect_b;
    const float3 reflect_origin = hit_point + reflect_dir * Constants::CONTINUATION_RAY_OFFSET;
    optixTrace(
        params.handle,
        reflect_origin,
        reflect_dir,
        Constants::CONTINUATION_RAY_OFFSET,
        Constants::MAX_RAY_DISTANCE,
        0.0f,
        OptixVisibilityMask(255),
        OPTIX_RAY_FLAG_NONE,
        0,  // SBT offset
        1,  // SBT stride
        0,  // missSBTIndex
        reflect_r, reflect_g, reflect_b
    );

    // Compute refracted ray using Snell's law
    const float eta = n1 / n2;
    const float k = 1.0f - eta * eta * (1.0f - cos_theta * cos_theta);

    unsigned int refract_r, refract_g, refract_b;
    if (k < 0.0f) {
        // Total internal reflection - use reflected color
        refract_r = reflect_r;
        refract_g = reflect_g;
        refract_b = reflect_b;
    } else {
        // Compute refracted direction: t = η·d + (η·cos(θ) - √k)·n
        const float coeff = eta * cos_theta - sqrtf(k);
        const float3 refract_dir = make_float3(
            eta * ray_direction.x + coeff * normal.x,
            eta * ray_direction.y + coeff * normal.y,
            eta * ray_direction.z + coeff * normal.z
        );

        // Cast refracted ray
        const float3 refract_origin = hit_point + refract_dir * Constants::CONTINUATION_RAY_OFFSET;
        optixTrace(
            params.handle,
            refract_origin,
            refract_dir,
            Constants::CONTINUATION_RAY_OFFSET,
            Constants::MAX_RAY_DISTANCE,
            0.0f,
            OptixVisibilityMask(255),
            OPTIX_RAY_FLAG_NONE,
            0,  // SBT offset
            1,  // SBT stride
            0,  // missSBTIndex
            refract_r, refract_g, refract_b
        );
    }

    // Blend reflected and refracted rays based on Fresnel
    const unsigned int r = static_cast<unsigned int>(fresnel * reflect_r + (1.0f - fresnel) * refract_r);
    const unsigned int g = static_cast<unsigned int>(fresnel * reflect_g + (1.0f - fresnel) * refract_g);
    const unsigned int b = static_cast<unsigned int>(fresnel * reflect_b + (1.0f - fresnel) * refract_b);

    // Set payload (RGB color)
    optixSetPayload_0(r);
    optixSetPayload_1(g);
    optixSetPayload_2(b);
}
