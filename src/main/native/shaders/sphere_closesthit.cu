#include <optix.h>
#include "../include/OptiXData.h"

// Schlick's approximation for Fresnel reflection
// R(θ) = R₀ + (1 - R₀)(1 - cos θ)⁵
// where R₀ = ((n₁ - n₂) / (n₁ + n₂))²
__device__ float schlick_fresnel(float cos_theta, float ior) {
    // n₁ = 1.0 (air), n₂ = ior (sphere material)
    const float r0 = (1.0f - ior) / (1.0f + ior);
    const float r0_sq = r0 * r0;

    // Ensure cos_theta is in [0, 1]
    cos_theta = fmaxf(0.0f, fminf(1.0f, cos_theta));

    const float one_minus_cos = 1.0f - cos_theta;
    const float one_minus_cos_5 = one_minus_cos * one_minus_cos * one_minus_cos * one_minus_cos * one_minus_cos;

    return r0_sq + (1.0f - r0_sq) * one_minus_cos_5;
}

// Closest hit shader - computes Lambertian shading with Fresnel reflection
extern "C" __global__ void __closesthit__ch() {
    // Get hit group data (light and material parameters)
    const HitGroupData* hit_data = reinterpret_cast<HitGroupData*>(optixGetSbtDataPointer());

    // Get hit point (ray origin + t * ray direction)
    const float t = optixGetRayTmax();
    const float3 ray_origin = optixGetWorldRayOrigin();
    const float3 ray_direction = optixGetWorldRayDirection();

    const float3 hit_point = make_float3(
        ray_origin.x + t * ray_direction.x,
        ray_origin.y + t * ray_direction.y,
        ray_origin.z + t * ray_direction.z
    );

    // Compute surface normal: normal = (hit_point - sphere_center) / radius
    const float3 sphere_center = make_float3(
        hit_data->sphere_center[0],
        hit_data->sphere_center[1],
        hit_data->sphere_center[2]
    );

    float3 normal = make_float3(
        hit_point.x - sphere_center.x,
        hit_point.y - sphere_center.y,
        hit_point.z - sphere_center.z
    );
    const float len = sqrtf(normal.x * normal.x + normal.y * normal.y + normal.z * normal.z);
    normal.x /= len;
    normal.y /= len;
    normal.z /= len;

    // Compute view direction (from hit point to camera)
    float3 view_dir = make_float3(-ray_direction.x, -ray_direction.y, -ray_direction.z);

    // Compute Fresnel reflection coefficient using Schlick's approximation
    const float cos_theta = view_dir.x * normal.x + view_dir.y * normal.y + view_dir.z * normal.z;
    const float fresnel = schlick_fresnel(cos_theta, hit_data->ior);

    // Light direction (negated for dot product)
    const float3 light_dir = make_float3(
        -hit_data->light_dir[0],
        -hit_data->light_dir[1],
        -hit_data->light_dir[2]
    );

    // Lambertian shading: max(0, N · L)
    float ndotl = normal.x * light_dir.x + normal.y * light_dir.y + normal.z * light_dir.z;
    ndotl = fmaxf(0.0f, ndotl);

    // WORKAROUND for CUDA optimizer bug: Load sphere_color into separate variables FIRST
    // before any computation. The optimizer incorrectly eliminates loads when all channels
    // use identical operations. Loading into named variables forces the loads to occur.
    const float color_r = hit_data->sphere_color[0];
    const float color_g = hit_data->sphere_color[1];
    const float color_b = hit_data->sphere_color[2];

    // Apply light intensity
    const float intensity = ndotl * hit_data->light_intensity;

    // Modulate by Fresnel term (for now, simple visualization)
    // Higher Fresnel = more reflection-like (brighter at grazing angles)
    const float final_intensity = intensity * (0.3f + 0.7f * fresnel);
    const float scale = 255.99f;

    // Apply material color and convert to RGB [0, 255]
    const unsigned int r = static_cast<unsigned int>(color_r * final_intensity * scale);
    const unsigned int g = static_cast<unsigned int>(color_g * final_intensity * scale);
    const unsigned int b = static_cast<unsigned int>(color_b * final_intensity * scale);

    // Set payload (RGB color)
    optixSetPayload_0(r);
    optixSetPayload_1(g);
    optixSetPayload_2(b);
}
