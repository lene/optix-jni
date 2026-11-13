#ifndef TRACE_DEPTH_PACKING_H
#define TRACE_DEPTH_PACKING_H

#include <cuda_runtime.h>

/**
 * The bit layout of payload 3 of a radiance ray, shared by host (C++) and device (CUDA) code.
 *
 * Payload 3 carries three values of a radiance ray:
 *   bits  0-7   the bounce depth that params.max_ray_depth budgets (reflections / refractions);
 *   bits  8-15  how many optixTrace calls are nested on the stack for this ray, including its own;
 *   bits 16-31  the ray's weight as a 16-bit fraction (65535 = 1.0): the product of the blend
 *               factors of all branches above it. A branch whose weight would fall below
 *               params.min_ray_weight is not traced (optix-jni#118).
 *
 * Only the pure bit functions live here, so the host gtests (TraceDepthPackingTest) cover the code
 * the GPU runs. The functions that read the live payload (canNest, child, worthTracing) are in
 * helpers.cu, namespace TraceDepth, and call these.
 */
namespace TraceDepth {
    constexpr unsigned int BOUNCE_BITS = 8u;
    constexpr unsigned int NEST_BITS = 8u;
    constexpr unsigned int WEIGHT_BITS = 16u;

    constexpr unsigned int NEST_SHIFT = BOUNCE_BITS;
    constexpr unsigned int WEIGHT_SHIFT = BOUNCE_BITS + NEST_BITS;

    constexpr unsigned int BOUNCE_MASK = (1u << BOUNCE_BITS) - 1u;
    constexpr unsigned int NEST_MASK = (1u << NEST_BITS) - 1u;
    constexpr unsigned int WEIGHT_MASK = (1u << WEIGHT_BITS) - 1u;

    // The weight field of a ray that carries all of the light.
    constexpr unsigned int WEIGHT_ONE = WEIGHT_MASK;

    __host__ __device__ inline unsigned int bounce(unsigned int payload) {
        return payload & BOUNCE_MASK;
    }

    __host__ __device__ inline unsigned int nesting(unsigned int payload) {
        return (payload >> NEST_SHIFT) & NEST_MASK;
    }

    // The weight as a float in [0, 1].
    __host__ __device__ inline float weight(unsigned int payload) {
        return static_cast<float>((payload >> WEIGHT_SHIFT) & WEIGHT_MASK)
            / static_cast<float>(WEIGHT_ONE);
    }

    // A weight as its 16-bit field, rounded to nearest; clamped to [0, 1].
    __host__ __device__ inline unsigned int weightField(float w) {
        const float clamped = w < 0.0f ? 0.0f : (w > 1.0f ? 1.0f : w);
        return static_cast<unsigned int>(clamped * static_cast<float>(WEIGHT_ONE) + 0.5f);
    }

    __host__ __device__ inline unsigned int pack(
        unsigned int bounce_depth, unsigned int nest, float w) {
        return (bounce_depth & BOUNCE_MASK)
            | ((nest & NEST_MASK) << NEST_SHIFT)
            | (weightField(w) << WEIGHT_SHIFT);
    }

    // For the radiance ray a raygen program traces: no bounces yet, one trace on the stack, all
    // of the light.
    __host__ __device__ inline unsigned int primary() {
        return pack(0u, 1u, 1.0f);
    }

    // The payload of a ray traced from a hit of the ray with payload `parent`: one level deeper
    // on the stack and carrying `factor` (clamped to [0, 1]) of the parent's weight.
    __host__ __device__ inline unsigned int childOf(
        unsigned int parent, unsigned int bounce_depth, float factor) {
        const float f = factor < 0.0f ? 0.0f : (factor > 1.0f ? 1.0f : factor);
        return pack(bounce_depth, nesting(parent) + 1u, weight(parent) * f);
    }
}

#endif  // TRACE_DEPTH_PACKING_H
