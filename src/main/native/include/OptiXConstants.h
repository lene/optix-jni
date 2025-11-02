#pragma once

// Constants for OptiX configuration
namespace OptiXConstants {
    constexpr size_t LOG_BUFFER_SIZE = 2048;
    constexpr unsigned int OPTIX_LOG_LEVEL_INFO = 3;  // Print Info, Warning, and Error messages

    // Default background color (dark purple/maroon)
    constexpr float DEFAULT_BG_R = 0.3f;
    constexpr float DEFAULT_BG_G = 0.1f;
    constexpr float DEFAULT_BG_B = 0.2f;

    // Ray tracing configuration for transparency
    constexpr unsigned int MAX_TRACE_DEPTH = 2;  // Support one level of transparency (primary + continuation)
}
