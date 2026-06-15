#include "include/DenoiserManager.h"

#include "include/OptiXErrorChecking.h"

#include <algorithm>
#include <iostream>
#include <optix_stubs.h>

namespace {
constexpr unsigned int FLOAT4_COMPONENTS = 4;

size_t maxScratchSize(const OptixDenoiserSizes& sizes) {
    return std::max(sizes.withoutOverlapScratchSizeInBytes, sizes.computeIntensitySizeInBytes);
}
} // namespace

DenoiserManager::DenoiserManager(
    OptixDeviceContext context,
    bool guide_albedo,
    bool guide_normal
) : guide_albedo_(guide_albedo), guide_normal_(guide_normal) {
    if (context == nullptr) {
        throw std::runtime_error("OptiX context is null");
    }

    OptixDenoiserOptions options = {};
    options.guideAlbedo = guide_albedo ? 1u : 0u;
    options.guideNormal = guide_normal ? 1u : 0u;
    options.denoiseAlpha = OPTIX_DENOISER_ALPHA_MODE_COPY;

    OPTIX_CHECK(optixDenoiserCreate(
        context,
        OPTIX_DENOISER_MODEL_KIND_HDR,
        &options,
        &denoiser_
    ));
}

DenoiserManager::~DenoiserManager() {
    if (denoiser_ != nullptr) {
        optixDenoiserDestroy(denoiser_);
        denoiser_ = nullptr;
    }
}

bool DenoiserManager::denoiseFloat4(
    int width,
    int height,
    CUdeviceptr input,
    CUdeviceptr output,
    CUdeviceptr albedo,
    CUdeviceptr normal
) {
    if (denoiser_ == nullptr || width <= 0 || height <= 0 || input == 0 || output == 0) {
        return false;
    }
    if ((guide_albedo_ && albedo == 0) || (guide_normal_ && normal == 0)) {
        return false;
    }

    try {
        ensureSetup(width, height);

        OptixDenoiserGuideLayer guide_layer = {};
        if (guide_albedo_) {
            guide_layer.albedo = makeImage(albedo, width, height);
        }
        if (guide_normal_) {
            guide_layer.normal = makeImage(normal, width, height);
        }

        OptixDenoiserLayer layer = {};
        layer.input = makeImage(input, width, height);
        layer.output = makeImage(output, width, height);
        layer.type = OPTIX_DENOISER_AOV_TYPE_BEAUTY;

        OPTIX_CHECK(optixDenoiserComputeIntensity(
            denoiser_,
            nullptr,
            &layer.input,
            hdr_intensity_buffer_.get(),
            scratch_buffer_.get(),
            sizes_.computeIntensitySizeInBytes
        ));

        OptixDenoiserParams params = {};
        params.hdrIntensity = hdr_intensity_buffer_.get();
        params.blendFactor = 0.0f;

        OPTIX_CHECK(optixDenoiserInvoke(
            denoiser_,
            nullptr,
            &params,
            state_buffer_.get(),
            sizes_.stateSizeInBytes,
            &guide_layer,
            &layer,
            1,
            0,
            0,
            scratch_buffer_.get(),
            scratch_size_
        ));

        CUDA_CHECK(cudaDeviceSynchronize());
        return true;
    } catch (const std::exception& e) {
        std::cerr << "[OptiX] Denoise failed: " << e.what() << std::endl;
        return false;
    }
}

OptixImage2D DenoiserManager::makeImage(CUdeviceptr data, int width, int height) const {
    OptixImage2D image = {};
    image.data = data;
    image.width = static_cast<unsigned int>(width);
    image.height = static_cast<unsigned int>(height);
    image.rowStrideInBytes = static_cast<unsigned int>(width) * FLOAT4_COMPONENTS * sizeof(float);
    image.pixelStrideInBytes = FLOAT4_COMPONENTS * sizeof(float);
    image.format = OPTIX_PIXEL_FORMAT_FLOAT4;
    return image;
}

void DenoiserManager::ensureSetup(int width, int height) {
    const unsigned int requested_width = static_cast<unsigned int>(width);
    const unsigned int requested_height = static_cast<unsigned int>(height);
    if (requested_width <= setup_width_ && requested_height <= setup_height_) {
        return;
    }

    OPTIX_CHECK(optixDenoiserComputeMemoryResources(
        denoiser_,
        requested_width,
        requested_height,
        &sizes_
    ));

    scratch_size_ = maxScratchSize(sizes_);
    state_buffer_.allocate(sizes_.stateSizeInBytes);
    scratch_buffer_.allocate(scratch_size_);
    hdr_intensity_buffer_.allocate(1);

    OPTIX_CHECK(optixDenoiserSetup(
        denoiser_,
        nullptr,
        requested_width,
        requested_height,
        state_buffer_.get(),
        sizes_.stateSizeInBytes,
        scratch_buffer_.get(),
        scratch_size_
    ));

    setup_width_ = requested_width;
    setup_height_ = requested_height;
}
