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
    bool guide_normal,
    bool temporal
) : guide_albedo_(guide_albedo), guide_normal_(guide_normal), temporal_(temporal) {
    if (context == nullptr) {
        throw std::runtime_error("OptiX context is null");
    }

    OptixDenoiserOptions options = {};
    options.guideAlbedo = guide_albedo ? 1u : 0u;
    options.guideNormal = guide_normal ? 1u : 0u;
    options.denoiseAlpha = OPTIX_DENOISER_ALPHA_MODE_COPY;

    const OptixDenoiserModelKind model_kind =
        temporal ? OPTIX_DENOISER_MODEL_KIND_TEMPORAL : OPTIX_DENOISER_MODEL_KIND_HDR;

    OPTIX_CHECK(optixDenoiserCreate(
        context,
        model_kind,
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
    CUdeviceptr normal,
    CUdeviceptr flow
) {
    if (denoiser_ == nullptr || width <= 0 || height <= 0 || input == 0 || output == 0) {
        return false;
    }
    if ((guide_albedo_ && albedo == 0) || (guide_normal_ && normal == 0)) {
        return false;
    }

    try {
        ensureSetup(width, height);
        if (temporal_) {
            const size_t npix = static_cast<size_t>(width) * static_cast<size_t>(height) * 4;
            const size_t new_size_bytes = npix * sizeof(float);
            if (previous_output_buffer_.sizeBytes() != new_size_bytes) {
                // A resolution change reallocates the buffer below, discarding whatever
                // previous-frame output it held -- any stale has_previous_output_=true from
                // before the resize would tell OptiX to blend against uninitialized memory.
                has_previous_output_ = false;
            }
            previous_output_buffer_.allocate(npix);
            // Same requirement as guide_layer.flow below: OptiX rejects a null
            // previousOutput data pointer even when temporalModeUsePreviousLayers is 0,
            // so zero-fill on the first call before any real output has been captured.
            if (!has_previous_output_) {
                previous_output_buffer_.zero(npix);
            }
        }

        OptixDenoiserGuideLayer guide_layer = {};
        if (guide_albedo_) {
            guide_layer.albedo = makeImage(albedo, width, height);
        }
        if (guide_normal_) {
            guide_layer.normal = makeImage(normal, width, height);
        }
        if (temporal_) {
            // OPTIX_DENOISER_MODEL_KIND_TEMPORAL requires guide_layer.flow to always carry
            // a valid (non-null) image, even on the very first temporal-mode frame when the
            // caller has no real flow data yet (temporalModeUsePreviousLayers is 0 then, so
            // its contents don't influence the result) -- optixDenoiserInvoke otherwise
            // rejects the call with OPTIX_ERROR_INVALID_VALUE ("guide flow: data pointer null").
            CUdeviceptr flow_ptr = flow;
            if (flow_ptr == 0) {
                const size_t npix2 = static_cast<size_t>(width) * static_cast<size_t>(height) * 2;
                zero_flow_buffer_.allocate(npix2);
                zero_flow_buffer_.zero(npix2);
                flow_ptr = zero_flow_buffer_.get();
            }
            OptixImage2D flow_image = {};
            flow_image.data = flow_ptr;
            flow_image.width = static_cast<unsigned int>(width);
            flow_image.height = static_cast<unsigned int>(height);
            flow_image.rowStrideInBytes = static_cast<unsigned int>(width) * 2 * sizeof(float);
            flow_image.pixelStrideInBytes = 2 * sizeof(float);
            flow_image.format = OPTIX_PIXEL_FORMAT_FLOAT2;
            guide_layer.flow = flow_image;
        }

        OptixDenoiserLayer layer = {};
        layer.input = makeImage(input, width, height);
        layer.output = makeImage(output, width, height);
        layer.type = OPTIX_DENOISER_AOV_TYPE_BEAUTY;
        if (temporal_) {
            layer.previousOutput = makeImage(previous_output_buffer_.get(), width, height);
        }

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
        params.temporalModeUsePreviousLayers = (temporal_ && has_previous_output_) ? 1u : 0u;
        last_invoke_used_previous_layers_ = params.temporalModeUsePreviousLayers != 0u;

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

        if (temporal_) {
            CUDA_CHECK(cudaMemcpy(
                reinterpret_cast<void*>(previous_output_buffer_.get()),
                reinterpret_cast<void*>(output),
                static_cast<size_t>(width) * static_cast<size_t>(height) * 4 * sizeof(float),
                cudaMemcpyDeviceToDevice
            ));
            has_previous_output_ = true;
        }

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
