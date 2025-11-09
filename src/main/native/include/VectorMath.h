#ifndef VECTOR_MATH_H
#define VECTOR_MATH_H

#include <cuda_runtime.h>
#include <cmath>

/**
 * Shared vector math utilities for both host (C++) and device (CUDA) code.
 * All functions are annotated with __host__ __device__ for dual compilation.
 */

// Length of a float3 vector
__host__ __device__ inline float length(float3 v) {
#ifdef __CUDA_ARCH__
    return sqrtf(v.x * v.x + v.y * v.y + v.z * v.z);
#else
    return std::sqrt(v.x * v.x + v.y * v.y + v.z * v.z);
#endif
}

// Normalize a float3 vector
__host__ __device__ inline float3 normalize(float3 v) {
    const float len = length(v);
    return make_float3(v.x / len, v.y / len, v.z / len);
}

// Dot product
__host__ __device__ inline float dot(float3 a, float3 b) {
    return a.x * b.x + a.y * b.y + a.z * b.z;
}

// Vector addition
__host__ __device__ inline float3 operator+(float3 a, float3 b) {
    return make_float3(a.x + b.x, a.y + b.y, a.z + b.z);
}

// Vector subtraction
__host__ __device__ inline float3 operator-(float3 a, float3 b) {
    return make_float3(a.x - b.x, a.y - b.y, a.z - b.z);
}

// Scalar multiplication (vector * scalar)
__host__ __device__ inline float3 operator*(float3 v, float s) {
    return make_float3(v.x * s, v.y * s, v.z * s);
}

// Scalar multiplication (scalar * vector)
__host__ __device__ inline float3 operator*(float s, float3 v) {
    return make_float3(v.x * s, v.y * s, v.z * s);
}

// Component-wise multiplication
__host__ __device__ inline float3 operator*(float3 a, float3 b) {
    return make_float3(a.x * b.x, a.y * b.y, a.z * b.z);
}

// Scalar division
__host__ __device__ inline float3 operator/(float3 v, float s) {
    return make_float3(v.x / s, v.y / s, v.z / s);
}

// Cross product
__host__ __device__ inline float3 cross(float3 a, float3 b) {
    return make_float3(
        a.y * b.z - a.z * b.y,
        a.z * b.x - a.x * b.z,
        a.x * b.y - a.y * b.x
    );
}

// Legacy array-based functions for host code compatibility
namespace VectorMath {
    // Normalize a 3D vector in place (array version)
    inline void normalize3f(float v[3]) {
        const float len = std::sqrt(v[0]*v[0] + v[1]*v[1] + v[2]*v[2]);
        v[0] /= len;
        v[1] /= len;
        v[2] /= len;
    }

    // Cross product: result = a × b (array version)
    inline void cross3f(float result[3], const float a[3], const float b[3]) {
        result[0] = a[1]*b[2] - a[2]*b[1];
        result[1] = a[2]*b[0] - a[0]*b[2];
        result[2] = a[0]*b[1] - a[1]*b[0];
    }
}

#endif // VECTOR_MATH_H
