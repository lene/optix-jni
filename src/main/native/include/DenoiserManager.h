#ifndef DENOISER_MANAGER_H
#define DENOISER_MANAGER_H

#include "CudaBuffer.h"

#include <cuda_runtime.h>
#include <optix.h>

class DenoiserManager {
public:
    DenoiserManager(OptixDeviceContext context, bool guide_albedo, bool guide_normal);
    ~DenoiserManager();

    DenoiserManager(const DenoiserManager&) = delete;
    DenoiserManager& operator=(const DenoiserManager&) = delete;

    bool usesAlbedoGuide() const { return guide_albedo_; }
    bool usesNormalGuide() const { return guide_normal_; }

    bool denoiseFloat4(
        int width,
        int height,
        CUdeviceptr input,
        CUdeviceptr output,
        CUdeviceptr albedo,
        CUdeviceptr normal
    );

private:
    OptixImage2D makeImage(CUdeviceptr data, int width, int height) const;
    void ensureSetup(int width, int height);

    OptixDenoiser denoiser_ = nullptr;
    bool guide_albedo_ = false;
    bool guide_normal_ = false;
    unsigned int setup_width_ = 0;
    unsigned int setup_height_ = 0;
    size_t scratch_size_ = 0;
    OptixDenoiserSizes sizes_{};
    CudaBuffer<unsigned char> state_buffer_;
    CudaBuffer<unsigned char> scratch_buffer_;
    CudaBuffer<float> hdr_intensity_buffer_;
};

#endif // DENOISER_MANAGER_H
