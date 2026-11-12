#ifndef ENV_MAP_MAPPING_H
#define ENV_MAP_MAPPING_H

#include <cuda_runtime.h>
#include <cmath>

/**
 * Equirectangular environment-map mapping shared by host (C++) and device (CUDA) code.
 *
 * The background (miss_plane.cu) and image-based lighting (helpers.cu) must use these
 * functions and only these (optix-jni#112): envDirToUv and envUvToDir are exact inverses, so the
 * light a bright texel casts comes from the direction in which the background shows it. One body
 * serves host and device, so the host gtests (EnvMapMappingTest) cover the code the GPU runs.
 *
 * Convention: u = 0.5 is +x, u = 0.75 is +z, u = 0.25 is -z; v = 0 is +y, v = 1 is -y.
 */

static constexpr float kEnvPi = 3.14159265358979323846f;
static constexpr float kEnvInvPi = 0.31830988618379067f;

// Unit direction -> texture coordinates in [0,1]^2. The caller normalises: asin of an
// unnormalised y is wrong, so this does not normalise itself.
__host__ __device__ inline float2 envDirToUv(float3 d) {
    const float y = d.y < -1.0f ? -1.0f : (d.y > 1.0f ? 1.0f : d.y);
    return make_float2(0.5f + atan2f(d.z, d.x) * (0.5f * kEnvInvPi),
                       0.5f - asinf(y) * kEnvInvPi);
}

// sin(theta) of the polar angle theta = v*pi; the solid-angle Jacobian of the texel at row v.
__host__ __device__ inline float envUvSinTheta(float v) {
    return sinf(v * kEnvPi);
}

// Texture coordinates -> unit direction; the inverse of envDirToUv.
__host__ __device__ inline float3 envUvToDir(float u, float v) {
    const float theta = v * kEnvPi;
    const float phi = (u - 0.5f) * 2.0f * kEnvPi;
    const float sin_theta = envUvSinTheta(v);
    return make_float3(sin_theta * cosf(phi), cosf(theta), sin_theta * sinf(phi));
}

#endif  // ENV_MAP_MAPPING_H
