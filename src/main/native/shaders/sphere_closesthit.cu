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

// Refract a vector using Snell's law
// Returns refracted direction, or zero vector if total internal reflection occurs
// n1: IOR of incident medium, n2: IOR of transmitted medium
__device__ float3 refract(const float3& incident, const float3& normal, float n1, float n2) {
    const float eta = n1 / n2;
    const float cos_i = -(incident.x * normal.x + incident.y * normal.y + incident.z * normal.z);
    const float sin_t2 = eta * eta * (1.0f - cos_i * cos_i);

    // Check for total internal reflection
    if (sin_t2 > 1.0f) {
        return make_float3(0.0f, 0.0f, 0.0f);
    }

    const float cos_t = sqrtf(1.0f - sin_t2);

    // Refracted direction = eta * incident + (eta * cos_i - cos_t) * normal
    return make_float3(
        eta * incident.x + (eta * cos_i - cos_t) * normal.x,
        eta * incident.y + (eta * cos_i - cos_t) * normal.y,
        eta * incident.z + (eta * cos_i - cos_t) * normal.z
    );
}

// Reflect a vector around a normal
__device__ float3 reflect(const float3& incident, const float3& normal) {
    const float dot = incident.x * normal.x + incident.y * normal.y + incident.z * normal.z;
    return make_float3(
        incident.x - 2.0f * dot * normal.x,
        incident.y - 2.0f * dot * normal.y,
        incident.z - 2.0f * dot * normal.z
    );
}

// Closest hit shader - computes shading with Fresnel reflection and refraction
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

    // Compute refracted ray direction (air -> sphere: n1=1.0, n2=ior)
    const float3 refracted_dir = refract(ray_direction, normal, 1.0f, hit_data->ior);

    // Check if total internal reflection occurred (refracted_dir is zero vector)
    const bool has_refraction = (refracted_dir.x != 0.0f || refracted_dir.y != 0.0f || refracted_dir.z != 0.0f);

    float final_intensity;
    if (has_refraction) {
        // Mix reflection and refraction based on Fresnel coefficient
        // For visualization: use Fresnel to blend between diffuse shading and enhanced brightness
        // Full ray tracing would trace reflected and refracted rays here
        final_intensity = intensity * (0.2f + 0.8f * (1.0f - fresnel));  // Less reflection = more transmission
    } else {
        // Total internal reflection - fully reflective
        final_intensity = intensity * 1.5f;  // Boost for visibility
    }

    const float scale = 255.99f;

    // Apply material color and convert to RGB [0, 255]
    const unsigned int r = static_cast<unsigned int>(fminf(color_r * final_intensity * scale, 255.0f));
    const unsigned int g = static_cast<unsigned int>(fminf(color_g * final_intensity * scale, 255.0f));
    const unsigned int b = static_cast<unsigned int>(fminf(color_b * final_intensity * scale, 255.0f));

    // Set payload (RGB color)
    optixSetPayload_0(r);
    optixSetPayload_1(g);
    optixSetPayload_2(b);
}
