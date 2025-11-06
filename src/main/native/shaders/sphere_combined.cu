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

__device__ inline float3 operator*(float3 a, float3 b) {
    return make_float3(a.x * b.x, a.y * b.y, a.z * b.z);
}

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
__device__ inline float3 computeBeerLambertAbsorption(
    const float3 color_rgb,
    const float alpha,
    const float distance)
{
    // Absorption factor: higher alpha = more absorption (standard convention)
    const float absorption_factor = alpha;

    // Avoid log(0) by clamping color channels
    const float3 safe_color = make_float3(
        fmaxf(color_rgb.x, 0.01f),
        fmaxf(color_rgb.y, 0.01f),
        fmaxf(color_rgb.z, 0.01f)
    );

    // Absorption coefficient for each wavelength
    // -log(color) gives absorption: dark colors (low values) = high absorption
    const float3 absorption_coeff = make_float3(
        -logf(safe_color.x) * absorption_factor,
        -logf(safe_color.y) * absorption_factor,
        -logf(safe_color.z) * absorption_factor
    );

    // Apply Beer-Lambert law: exp(-α · d)
    return make_float3(
        expf(-absorption_coeff.x * distance),
        expf(-absorption_coeff.y * distance),
        expf(-absorption_coeff.z * distance)
    );
}

//==============================================================================
// Custom Sphere Intersection Program
// Based on NVIDIA OptiX SDK 9.0 sphere.cu example
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

    // Vector from ray origin to sphere center
    const float3 oc = make_float3(
        ray_orig.x - center.x,
        ray_orig.y - center.y,
        ray_orig.z - center.z
    );

    // Standard ray-sphere intersection using quadratic formula
    // Ray equation: P(t) = origin + t * direction
    // Sphere equation: |P - center|^2 = radius^2
    // Substitute and solve: at^2 + bt + c = 0
    const float a = dot(ray_dir, ray_dir);
    const float b = 2.0f * dot(ray_dir, oc);
    const float c = dot(oc, oc) - radius * radius;
    const float disc = b * b - 4.0f * a * c;

    if (disc >= 0.0f)
    {
        const float sqrt_disc = sqrtf(disc);

        // Two potential intersections: (-b ± sqrt(disc)) / (2a)
        const float t1 = (-b - sqrt_disc) / (2.0f * a);  // Near intersection
        const float t2 = (-b + sqrt_disc) / (2.0f * a);  // Far intersection

        // Try near intersection first (entry point)
        if (t1 > ray_tmin && t1 < ray_tmax)
        {
            // Compute hit point and normal
            const float3 hit_point = ray_orig + ray_dir * t1;
            const float3 normal = normalize(make_float3(
                hit_point.x - center.x,
                hit_point.y - center.y,
                hit_point.z - center.z
            ));

            optixReportIntersection(
                t1,
                0,  // hit_kind = 0 for entry
                __float_as_uint(normal.x),
                __float_as_uint(normal.y),
                __float_as_uint(normal.z)
            );
        }

        // Try far intersection (exit point) - always try both if in range
        if (t2 > ray_tmin && t2 < ray_tmax)
        {
            // Compute hit point and normal
            const float3 hit_point = ray_orig + ray_dir * t2;
            const float3 normal = normalize(make_float3(
                hit_point.x - center.x,
                hit_point.y - center.y,
                hit_point.z - center.z
            ));

            optixReportIntersection(
                t2,
                1,  // hit_kind = 1 for exit
                __float_as_uint(normal.x),
                __float_as_uint(normal.y),
                __float_as_uint(normal.z)
            );
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
    unsigned int p0, p1, p2, p3;  // Payload for RGB color + depth
    p3 = 0;  // Initial depth = 0
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

    // Get current depth from payload
    const unsigned int depth = optixGetPayload_3();
    const unsigned int MAX_DEPTH = 2;

    // If IOR is 1.0, treat as opaque sphere (no refraction/transmission)
    const bool is_opaque = (fabsf(hit_data->ior - 1.0f) < 0.01f);

    // Get sphere alpha
    const float sphere_alpha = hit_data->sphere_color[3];

    // Handle fully transparent spheres - trace continuation ray through sphere
    if (sphere_alpha < 0.01f) {
        // Sphere is fully transparent, let ray continue as if sphere doesn't exist
        const float3 continue_origin = hit_point + ray_direction * Constants::CONTINUATION_RAY_OFFSET;
        unsigned int continue_r = 0, continue_g = 0, continue_b = 0;
        unsigned int next_depth = depth;  // Don't increment depth for transparent pass-through

        optixTrace(
            params.handle,
            continue_origin,
            ray_direction,  // Continue in same direction
            Constants::CONTINUATION_RAY_OFFSET,
            Constants::MAX_RAY_DISTANCE,
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

    // If max depth reached or opaque sphere, return simple diffuse shading
    if (depth >= MAX_DEPTH || is_opaque) {
        const float3 light_dir_normalized = normalize(make_float3(
            hit_data->light_dir[0],
            hit_data->light_dir[1],
            hit_data->light_dir[2]
        ));
        const float diffuse = fmaxf(0.0f, dot(normal, light_dir_normalized));
        const float ambient = 0.3f;
        const float lighting = ambient + (1.0f - ambient) * diffuse;

        // Get sphere color
        const float3 sphere_color = make_float3(
            hit_data->sphere_color[0],
            hit_data->sphere_color[1],
            hit_data->sphere_color[2]
        );

        // Apply alpha-based opacity for opaque spheres
        // For opaque spheres, alpha controls opacity (standard graphics convention)
        // alpha=1.0 → fully opaque, alpha=0.0 → fully transparent
        const float3 lit_color = make_float3(
            sphere_color.x * lighting,
            sphere_color.y * lighting,
            sphere_color.z * lighting
        );

        // Simply scale by alpha (opacity)
        const float3 absorbed_color = lit_color * sphere_alpha;

        const unsigned int r = static_cast<unsigned int>(fminf(absorbed_color.x * 255.0f, 255.0f));
        const unsigned int g = static_cast<unsigned int>(fminf(absorbed_color.y * 255.0f, 255.0f));
        const unsigned int b = static_cast<unsigned int>(fminf(absorbed_color.z * 255.0f, 255.0f));

        optixSetPayload_0(r);
        optixSetPayload_1(g);
        optixSetPayload_2(b);
        return;
    }

    // Compute Fresnel reflectance using Schlick approximation
    const float n1 = entering ? 1.0f : hit_data->ior;
    const float n2 = entering ? hit_data->ior : 1.0f;
    const float r0 = (n1 - n2) / (n1 + n2);
    const float R0 = r0 * r0;
    const float cos_theta = fabsf(dot(ray_direction, normal));
    const float one_minus_cos = 1.0f - cos_theta;
    const float fresnel = R0 + (1.0f - R0) * one_minus_cos * one_minus_cos * one_minus_cos * one_minus_cos * one_minus_cos;

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

        const float3 refract_origin = hit_point + refract_dir * Constants::CONTINUATION_RAY_OFFSET;
        unsigned int next_depth = depth + 1;
        optixTrace(
            params.handle,
            refract_origin,
            refract_dir,
            Constants::CONTINUATION_RAY_OFFSET,
            Constants::MAX_RAY_DISTANCE,
            0.0f,
            OptixVisibilityMask(255),
            OPTIX_RAY_FLAG_NONE,
            0, 1, 0,
            refract_r, refract_g, refract_b, next_depth
        );

        // Apply Beer-Lambert absorption if exiting glass
        if (!entering) {
            // Distance traveled through glass ≈ t (from refracted ray origin to exit)
            // Scale by physical scale parameter
            const float distance_in_glass = t * hit_data->scale;

            // Get glass color and alpha
            const float3 glass_color = make_float3(
                hit_data->sphere_color[0],
                hit_data->sphere_color[1],
                hit_data->sphere_color[2]
            );
            const float glass_alpha = hit_data->sphere_color[3];

            // Compute absorption for this distance
            const float3 absorption = computeBeerLambertAbsorption(
                glass_color,
                glass_alpha,
                distance_in_glass
            );

            // Apply absorption to refracted color
            const float3 refract_color = make_float3(
                static_cast<float>(refract_r) / 255.0f,
                static_cast<float>(refract_g) / 255.0f,
                static_cast<float>(refract_b) / 255.0f
            );
            const float3 absorbed_color = refract_color * absorption;

            // Convert back to unsigned int
            refract_r = static_cast<unsigned int>(fminf(absorbed_color.x * 255.0f, 255.0f));
            refract_g = static_cast<unsigned int>(fminf(absorbed_color.y * 255.0f, 255.0f));
            refract_b = static_cast<unsigned int>(fminf(absorbed_color.z * 255.0f, 255.0f));
        }
    }

    // Simple result: just use refracted color (no reflection for now to avoid stack overflow)
    optixSetPayload_0(refract_r);
    optixSetPayload_1(refract_g);
    optixSetPayload_2(refract_b);
}
