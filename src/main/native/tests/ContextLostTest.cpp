#include <gtest/gtest.h>
#include "../include/OptiXErrorChecking.h"

#include <string>

// optix-jni#73: which CUDA errors poison the context, and how the first one is latched.
// Host-only; no GPU call.

namespace {

class ContextLost : public ::testing::Test {
protected:
    void SetUp() override { clear(); }
    void TearDown() override { clear(); }
    static void clear() {
        std::lock_guard<std::mutex> lock(cuda_state::lostMutex());
        cuda_state::lostReason().clear();
    }
};

}  // namespace

TEST_F(ContextLost, StickyErrorsAreThoseThatPoisonTheContext) {
    EXPECT_TRUE(isStickyCudaError(cudaErrorIllegalAddress));    // 700
    EXPECT_TRUE(isStickyCudaError(cudaErrorLaunchFailure));     // 719
    EXPECT_TRUE(isStickyCudaError(cudaErrorMisalignedAddress)); // 716
    EXPECT_FALSE(isStickyCudaError(cudaErrorMemoryAllocation)); // out of memory: recoverable
    EXPECT_FALSE(isStickyCudaError(cudaErrorInvalidValue));
    EXPECT_FALSE(isStickyCudaError(cudaSuccess));
}

TEST_F(ContextLost, AStickyFailureThrowsContextLostAndLatches) {
    EXPECT_THROW(throwCudaFailure("boom", cudaErrorIllegalAddress), CudaContextLost);
    EXPECT_EQ(contextLostReason(), "boom");
    EXPECT_THROW(throwIfContextLost(), CudaContextLost);
}

TEST_F(ContextLost, ARecoverableFailureThrowsAPlainErrorAndDoesNotLatch) {
    try {
        throwCudaFailure("no memory", cudaErrorMemoryAllocation);
        FAIL() << "expected a throw";
    } catch (const CudaContextLost&) {
        FAIL() << "out of memory is recoverable";
    } catch (const std::runtime_error& e) {
        EXPECT_STREQ(e.what(), "no memory");
    }
    EXPECT_EQ(contextLostReason(), "");
    EXPECT_NO_THROW(throwIfContextLost());
}

TEST_F(ContextLost, TheFirstLossWins) {
    latchContextLost("first");
    latchContextLost("second");
    EXPECT_EQ(contextLostReason(), "first");
}

TEST_F(ContextLost, NoteCudaErrorLatchesStickyOnes) {
    noteCudaError(cudaErrorInvalidValue, "a setter");
    EXPECT_EQ(contextLostReason(), "");
    noteCudaError(cudaErrorLaunchFailure, "project4d kernel");
    EXPECT_NE(contextLostReason().find("project4d kernel"), std::string::npos);
    EXPECT_NE(contextLostReason().find("(719)"), std::string::npos);
}
