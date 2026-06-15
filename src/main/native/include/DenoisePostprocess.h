#ifndef DENOISE_POSTPROCESS_H
#define DENOISE_POSTPROCESS_H

#include <cuda_runtime.h>

cudaError_t launchAccumulateFloat4Kernel(
    const float4* frame,
    float4* accumulation,
    int pixel_count,
    int frame_index
);

cudaError_t launchFloat4ToRgba8Kernel(
    const float4* input,
    unsigned char* output,
    int pixel_count,
    int tonemap_operator,
    float exposure
);

#endif // DENOISE_POSTPROCESS_H
