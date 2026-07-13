#include <gtest/gtest.h>
#include "../include/DenoiserManager.h"
#include "../include/OptiXContext.h"
#include "../include/OptiXErrorChecking.h"

#include <vector>

// Test fixture: brings up a real OptiX device context (mirrors OptiXContextTest.cpp's
// pattern) since DenoiserManager requires a live OptixDeviceContext to construct.
class DenoiserManagerTest : public ::testing::Test {
protected:
    OptiXContext context;

    void SetUp() override {
        ASSERT_TRUE(context.initialize());
    }
};

namespace {

// RAII device buffer helper, local to this test file (avoids depending on
// CudaBuffer's private-to-production-code allocation patterns).
CUdeviceptr allocDevice(size_t float_count) {
    CUdeviceptr ptr = 0;
    CUDA_CHECK(cudaMalloc(reinterpret_cast<void**>(&ptr), float_count * sizeof(float)));
    CUDA_CHECK(cudaMemset(reinterpret_cast<void*>(ptr), 0, float_count * sizeof(float)));
    return ptr;
}

} // namespace

// Phase 4 Task 3: TEMPORAL model kind + previousOutput chaining across invokes.
TEST_F(DenoiserManagerTest, TemporalInvokeUsesPreviousLayersOnSecondCall) {
    constexpr int width = 16;
    constexpr int height = 16;
    const size_t npix4 = static_cast<size_t>(width) * height * 4;
    const size_t npix2 = static_cast<size_t>(width) * height * 2;

    DenoiserManager denoiser(context.getContext(), /*guide_albedo=*/false, /*guide_normal=*/false, /*temporal=*/true);
    EXPECT_TRUE(denoiser.isTemporal());

    CUdeviceptr input = allocDevice(npix4);
    CUdeviceptr output = allocDevice(npix4);
    CUdeviceptr flow = allocDevice(npix2);

    // First invoke: no previous output exists yet, no flow buffer supplied.
    const bool first_ok = denoiser.denoiseFloat4(width, height, input, output, 0, 0, 0);
    ASSERT_TRUE(first_ok);
    EXPECT_FALSE(denoiser.lastInvokeUsedPreviousLayers());

    // Second invoke: previousOutput now available from the first call, and a
    // valid flow buffer is supplied -- OptiX should be told to use previous layers.
    const bool second_ok = denoiser.denoiseFloat4(width, height, input, output, 0, 0, flow);
    ASSERT_TRUE(second_ok);
    EXPECT_TRUE(denoiser.lastInvokeUsedPreviousLayers());

    cudaFree(reinterpret_cast<void*>(input));
    cudaFree(reinterpret_cast<void*>(output));
    cudaFree(reinterpret_cast<void*>(flow));
}

// Regression guard: HDR (non-temporal) mode must remain unaffected.
TEST_F(DenoiserManagerTest, NonTemporalNeverReportsPreviousLayers) {
    constexpr int width = 16;
    constexpr int height = 16;
    const size_t npix4 = static_cast<size_t>(width) * height * 4;

    DenoiserManager denoiser(context.getContext(), /*guide_albedo=*/false, /*guide_normal=*/false);
    EXPECT_FALSE(denoiser.isTemporal());

    CUdeviceptr input = allocDevice(npix4);
    CUdeviceptr output = allocDevice(npix4);

    ASSERT_TRUE(denoiser.denoiseFloat4(width, height, input, output, 0, 0));
    EXPECT_FALSE(denoiser.lastInvokeUsedPreviousLayers());

    ASSERT_TRUE(denoiser.denoiseFloat4(width, height, input, output, 0, 0));
    EXPECT_FALSE(denoiser.lastInvokeUsedPreviousLayers());

    cudaFree(reinterpret_cast<void*>(input));
    cudaFree(reinterpret_cast<void*>(output));
}
