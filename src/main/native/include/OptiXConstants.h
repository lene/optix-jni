#pragma once

// C++-only OptiX configuration constants
// (Constants shared with shaders are in OptiXData.h)
namespace OptiXConstants {
    constexpr size_t LOG_BUFFER_SIZE = 2048;
    constexpr unsigned int OPTIX_LOG_LEVEL_INFO = 3;  // Print Info, Warning, and Error messages

    // Default background color (dark purple/maroon)
    constexpr float DEFAULT_BG_R = 0.3f;
    constexpr float DEFAULT_BG_G = 0.1f;
    constexpr float DEFAULT_BG_B = 0.2f;

    // CUDA error codes
    constexpr int CUDA_ERROR_INVALID_PROGRAM_COUNTER = 718;  // OptiX SDK/driver version mismatch

    // Ray tracing depth is now defined in OptiXData.h (shared with shaders)
}
