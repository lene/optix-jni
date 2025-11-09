#pragma once

#include <optix.h>
#include <cuda_runtime.h>
#include <sstream>
#include <stdexcept>
#include "OptiXConstants.h"

/**
 * @file OptiXErrorChecking.h
 * @brief Shared error checking macros for OptiX and CUDA API calls
 *
 * These macros provide consistent error handling across the OptiX JNI codebase.
 * They throw std::runtime_error with detailed diagnostic information on failure.
 */

/**
 * @brief Check OptiX API call return value and throw exception on error
 *
 * Usage: OPTIX_CHECK(optixDeviceContextCreate(...));
 *
 * On error, throws std::runtime_error with:
 * - The failed API call (stringified)
 * - OptiX error name (e.g., "OPTIX_ERROR_INVALID_VALUE")
 * - OptiX error code (numeric value)
 */
#define OPTIX_CHECK(call)                                                     \
    do {                                                                      \
        OptixResult res = call;                                               \
        if (res != OPTIX_SUCCESS) {                                           \
            std::ostringstream ss;                                            \
            ss << "OptiX call '" << #call << "' failed: "                     \
               << optixGetErrorName(res) << " (" << res << ")";               \
            throw std::runtime_error(ss.str());                               \
        }                                                                     \
    } while(0)

/**
 * @brief Check CUDA API call return value and throw exception on error
 *
 * Usage: CUDA_CHECK(cudaMalloc(&ptr, size));
 *
 * On error, throws std::runtime_error with:
 * - The failed API call (stringified)
 * - CUDA error string (e.g., "invalid argument")
 * - CUDA error code (numeric value)
 *
 * Special handling for CUDA error 718 (invalid program counter):
 * Provides detailed diagnostics for OptiX SDK/driver version mismatch,
 * including commands to diagnose and fix the issue.
 */
#define CUDA_CHECK(call)                                                      \
    do {                                                                      \
        cudaError_t err = call;                                               \
        if (err != cudaSuccess) {                                             \
            std::ostringstream ss;                                            \
            ss << "CUDA call '" << #call << "' failed: "                      \
               << cudaGetErrorString(err) << " (" << err << ")";              \
            if (err == OptiXConstants::CUDA_ERROR_INVALID_PROGRAM_COUNTER) {  \
                ss << "\n\n"                                                  \
                   << "ERROR 718 (invalid program counter) indicates OptiX " \
                   << "SDK/driver version mismatch.\n"                        \
                   << "To diagnose:\n"                                        \
                   << "  1. Check driver's OptiX version:\n"                 \
                   << "     strings /usr/lib/x86_64-linux-gnu/libnvoptix.so.* | grep 'OptiX Version'\n" \
                   << "  2. Check SDK version used to build:\n"              \
                   << "     grep 'OptiX SDK:' optix-jni/target/native/x86_64-linux/build/CMakeCache.txt\n" \
                   << "  3. Install matching OptiX SDK from https://developer.nvidia.com/optix\n" \
                   << "  4. Rebuild: rm -rf optix-jni/target/native && sbt 'project optixJni' compile\n"; \
            }                                                                 \
            throw std::runtime_error(ss.str());                               \
        }                                                                     \
    } while(0)
