#ifndef ENV_MAP_MAPPING_H
#define ENV_MAP_MAPPING_H

#include <cuda_runtime.h>
#include <cmath>

/**
 * Equirectangular environment-map mapping shared by host (C++) and device (CUDA) code.
 *
 * The background (miss_plane.cu) and image-based lighting (helpers.cu) must use these two
 * functions and only these (optix-jni#112): they are exact inverses of each other, so the
 * light a bright texel casts comes from the direction in which the background shows it.
 *
 * Convention: u = 0.5 is +x, u = 0.75 is +z, u = 0.25 is -z; v = 0 is +y, v = 1 is -y.
 */

// Direction (need not be normalised) -> texture coordinates in [0,1]^2.
__host__ __device__ inline float2 envDirToUv(float3 d) {
    const float inv_pi = 0.31830988618379067f;  // 1/pi
    const float y = d.y < -1.0f ? -1.0f : (d.y > 1.0f ? 1.0f : d.y);
#ifdef __CUDA_ARCH__
    const float u = 0.5f + atan2f(d.z, d.x) * (0.5f * inv_pi);
    const float v = 0.5f - asinf(y) * inv_pi;
#else
    const float u = 0.5f + std::atan2(d.z, d.x) * (0.5f * inv_pi);
    const float v = 0.5f - std::asin(y) * inv_pi;
#endif
    return make_float2(u, v);
}

// Texture coordinates -> unit direction; the inverse of envDirToUv.
__host__ __device__ inline float3 envUvToDir(float u, float v) {
    const float pi = 3.14159265358979323846f;
    const float theta = v * pi;
    const float phi = (u - 0.5f) * 2.0f * pi;
#ifdef __CUDA_ARCH__
    const float sin_theta = sinf(theta);
    return make_float3(sin_theta * cosf(phi), cosf(theta), sin_theta * sinf(phi));
#else
    const float sin_theta = std::sin(theta);
    return make_float3(sin_theta * std::cos(phi), std::cos(theta), sin_theta * std::sin(phi));
#endif
}

#endif  // ENV_MAP_MAPPING_H
