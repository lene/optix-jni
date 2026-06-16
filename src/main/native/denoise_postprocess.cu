#include "include/DenoisePostprocess.h"

namespace {
constexpr int THREADS_PER_BLOCK = 256;
constexpr int TONEMAP_REINHARD = 1;
constexpr int TONEMAP_ACES = 2;
constexpr float COLOR_SCALE_FACTOR = 255.99f;

__device__ float clamp01(float value) {
    return fminf(fmaxf(value, 0.0f), 1.0f);
}

__device__ float3 applyToneMapping(float3 color, int operator_id, float exposure) {
    if (operator_id == TONEMAP_REINHARD) {
        color.x = color.x * exposure / (1.0f + color.x * exposure);
        color.y = color.y * exposure / (1.0f + color.y * exposure);
        color.z = color.z * exposure / (1.0f + color.z * exposure);
    } else if (operator_id == TONEMAP_ACES) {
        color.x *= exposure;
        color.y *= exposure;
        color.z *= exposure;
        const float a = 2.51f;
        const float b = 0.03f;
        const float c = 2.43f;
        const float d = 0.59f;
        const float e = 0.14f;
        color.x = (color.x * (a * color.x + b)) / (color.x * (c * color.x + d) + e);
        color.y = (color.y * (a * color.y + b)) / (color.y * (c * color.y + d) + e);
        color.z = (color.z * (a * color.z + b)) / (color.z * (c * color.z + d) + e);
    } else {
        color.x *= exposure;
        color.y *= exposure;
        color.z *= exposure;
    }

    return make_float3(clamp01(color.x), clamp01(color.y), clamp01(color.z));
}

__global__ void accumulateFloat4Kernel(
    const float4* frame,
    float4* accumulation,
    int pixel_count,
    int frame_index
) {
    const int index = blockIdx.x * blockDim.x + threadIdx.x;
    if (index >= pixel_count) return;

    const float4 value = frame[index];
    if (frame_index <= 0) {
        accumulation[index] = value;
        return;
    }

    const float previous_weight = static_cast<float>(frame_index);
    const float inv_weight = 1.0f / static_cast<float>(frame_index + 1);
    const float4 previous = accumulation[index];
    accumulation[index] = make_float4(
        (previous.x * previous_weight + value.x) * inv_weight,
        (previous.y * previous_weight + value.y) * inv_weight,
        (previous.z * previous_weight + value.z) * inv_weight,
        (previous.w * previous_weight + value.w) * inv_weight
    );
}

__global__ void float4ToRgba8Kernel(
    const float4* input,
    unsigned char* output,
    int pixel_count,
    int tonemap_operator,
    float exposure
) {
    const int index = blockIdx.x * blockDim.x + threadIdx.x;
    if (index >= pixel_count) return;

    const float4 value = input[index];
    const float3 mapped = applyToneMapping(
        make_float3(value.x, value.y, value.z),
        tonemap_operator,
        exposure
    );

    output[index * 4 + 0] = static_cast<unsigned char>(mapped.x * COLOR_SCALE_FACTOR);
    output[index * 4 + 1] = static_cast<unsigned char>(mapped.y * COLOR_SCALE_FACTOR);
    output[index * 4 + 2] = static_cast<unsigned char>(mapped.z * COLOR_SCALE_FACTOR);
    output[index * 4 + 3] = static_cast<unsigned char>(clamp01(value.w) * COLOR_SCALE_FACTOR);
}
} // namespace

cudaError_t launchAccumulateFloat4Kernel(
    const float4* frame,
    float4* accumulation,
    int pixel_count,
    int frame_index
) {
    const int blocks = (pixel_count + THREADS_PER_BLOCK - 1) / THREADS_PER_BLOCK;
    accumulateFloat4Kernel<<<blocks, THREADS_PER_BLOCK>>>(
        frame,
        accumulation,
        pixel_count,
        frame_index
    );
    return cudaGetLastError();
}

cudaError_t launchFloat4ToRgba8Kernel(
    const float4* input,
    unsigned char* output,
    int pixel_count,
    int tonemap_operator,
    float exposure
) {
    const int blocks = (pixel_count + THREADS_PER_BLOCK - 1) / THREADS_PER_BLOCK;
    float4ToRgba8Kernel<<<blocks, THREADS_PER_BLOCK>>>(
        input,
        output,
        pixel_count,
        tonemap_operator,
        exposure
    );
    return cudaGetLastError();
}
