#pragma once

#include <cstdlib>
#include <cstring>

#include <optix_types.h>

// MENGER_OPTIX_DEBUG=1 turns on OptiX's own launch diagnostics: device-context validation mode
// (maximum log level), stack-overflow / trace-depth / user exceptions in every module and the
// pipeline, and an exception program (__exception__diagnostic) that prints which exception
// fired, where. Much slower -- for naming the cause of a launch failure (CUDA 700/719), never
// on by default. Read once per call site; the pipeline and all modules must agree, so every
// place that builds OptixPipelineCompileOptions asks this same function.
inline bool optixDiagnosticsEnabled() {
    const char* value = std::getenv("MENGER_OPTIX_DEBUG");
    return value != nullptr && std::strcmp(value, "1") == 0;
}

// OptixPipelineCompileOptions::exceptionFlags for every module and pipeline -- they must match
// across all modules linked into one pipeline, or optixPipelineCreate rejects the link.
inline unsigned int optixPipelineExceptionFlags() {
    return optixDiagnosticsEnabled()
        ? (OPTIX_EXCEPTION_FLAG_STACK_OVERFLOW | OPTIX_EXCEPTION_FLAG_TRACE_DEPTH
           | OPTIX_EXCEPTION_FLAG_USER)
        : OPTIX_EXCEPTION_FLAG_NONE;
}
