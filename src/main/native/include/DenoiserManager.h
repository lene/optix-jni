#ifndef DENOISER_MANAGER_H
#define DENOISER_MANAGER_H

#include "CudaBuffer.h"

#include <cuda_runtime.h>
#include <optix.h>

class DenoiserManager {
public:
    DenoiserManager(OptixDeviceContext context, bool guide_albedo, bool guide_normal, bool temporal = false);
    ~DenoiserManager();

    DenoiserManager(const DenoiserManager&) = delete;
    DenoiserManager& operator=(const DenoiserManager&) = delete;

    bool usesAlbedoGuide() const { return guide_albedo_; }
    bool usesNormalGuide() const { return guide_normal_; }
    bool isTemporal() const { return temporal_; }

    bool denoiseFloat4(
        int width,
        int height,
        CUdeviceptr input,
        CUdeviceptr output,
        CUdeviceptr albedo,
        CUdeviceptr normal,
        CUdeviceptr flow = 0
    );

    bool lastInvokeUsedPreviousLayers() const { return last_invoke_used_previous_layers_; }

private:
    OptixImage2D makeImage(CUdeviceptr data, int width, int height) const;
    void ensureSetup(int width, int height);

    OptixDenoiser denoiser_ = nullptr;
    bool guide_albedo_ = false;
    bool guide_normal_ = false;
    bool temporal_ = false;
    bool has_previous_output_ = false;
    bool last_invoke_used_previous_layers_ = false;
    unsigned int setup_width_ = 0;
    unsigned int setup_height_ = 0;
    size_t scratch_size_ = 0;
    OptixDenoiserSizes sizes_{};
    CudaBuffer<unsigned char> state_buffer_;
    CudaBuffer<unsigned char> scratch_buffer_;
    CudaBuffer<float> hdr_intensity_buffer_;
    CudaBuffer<float> previous_output_buffer_;  // float4 per pixel, temporal mode only
    CudaBuffer<float> zero_flow_buffer_;  // float2 per pixel; fallback when caller passes flow=0
};

#endif // DENOISER_MANAGER_H
