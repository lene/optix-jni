#pragma once

#include <optix.h>
#include <cuda_runtime.h>
#include <algorithm>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>
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
            throw std::runtime_error(formatCudaError(#call, err));            \
        }                                                                     \
    } while(0)

/**
 * @brief CUDA_CHECK's message: "CUDA call '<call>' failed: <description> (<code>)"
 *
 * Downstream callers (e.g. menger's fail-fast on sticky CUDA errors) parse this format, so it
 * must stay stable.
 */
inline std::string formatCudaError(const char* call, cudaError_t err) {
    std::ostringstream ss;
    ss << "CUDA call '" << call << "' failed: "
       << cudaGetErrorString(err) << " (" << err << ")";
    if (err == OptiXConstants::CUDA_ERROR_INVALID_PROGRAM_COUNTER) {
        ss << "\n\n"
           << "ERROR 718 (invalid program counter) indicates OptiX "
           << "SDK/driver version mismatch.\n"
           << "To diagnose:\n"
           << "  1. Check driver's OptiX version:\n"
           << "     strings /usr/lib/x86_64-linux-gnu/libnvoptix.so.* | grep 'OptiX Version'\n"
           << "  2. Check SDK version used to build:\n"
           << "     grep 'OptiX SDK:' optix-jni/target/native/x86_64-linux/build/CMakeCache.txt\n"
           << "  3. Install matching OptiX SDK from https://developer.nvidia.com/optix\n"
           << "  4. Rebuild: rm -rf optix-jni/target/native && sbt 'project optixJni' compile\n";
    }
    return ss.str();
}

/**
 * @brief The message for a launch whose completion failed, led by what OptiX logged meanwhile
 *
 * OptiX can report a launch problem only through its log callback while optixLaunch itself
 * returns OPTIX_SUCCESS -- e.g. failing to grow the per-thread stack ("local memory") pool,
 * after which the queued kernel is rejected by the GPU and only a generic CUDA error surfaces
 * at the next synchronize. Those logged messages are the real cause, so they come first,
 * quoted verbatim; only the one message whose meaning is known gets a plain-language
 * explanation. The CUDA error stays intact as the last line. Without logged errors the CUDA
 * error is returned unchanged.
 */
inline std::string formatLaunchFailure(const std::vector<std::string>& optix_errors,
                                       const std::string& cuda_error) {
    if (optix_errors.empty()) {
        return cuda_error;
    }
    const bool stack_memory_exhausted = std::any_of(
        optix_errors.begin(), optix_errors.end(), [](const std::string& message) {
            return message.find("ReconfigureLocalMemory") != std::string::npos
                && message.find("out of memory") != std::string::npos;
        });
    std::ostringstream ss;
    if (stack_memory_exhausted) {
        ss << "OptiX render launch failed: the GPU ran out of memory for the renderer's "
           << "per-thread stack.\n"
           << "Free GPU memory (e.g. close other GPU applications) or reduce the resolution "
           << "or scene complexity.\n";
    } else {
        ss << "OptiX render launch failed.\n";
    }
    for (const auto& message : optix_errors) {
        ss << "OptiX reported during this launch: " << message << "\n";
    }
    ss << cuda_error;
    return ss.str();
}
