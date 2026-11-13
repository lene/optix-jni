#include <gtest/gtest.h>
#include "TraceDepthPacking.h"

// Payload 3 of a radiance ray packs the bounce depth, the nesting count and the ray's weight
// (optix-jni#118). These tests pin the layout the shaders rely on.

namespace {
constexpr float kWeightStep = 1.0f / 65535.0f;
}

TEST(TraceDepthPackingTest, BounceNestingAndWeightSurvivePack) {
    const unsigned int p = TraceDepth::pack(5u, 17u, 0.25f);
    EXPECT_EQ(TraceDepth::bounce(p), 5u);
    EXPECT_EQ(TraceDepth::nesting(p), 17u);
    EXPECT_NEAR(TraceDepth::weight(p), 0.25f, kWeightStep);
}

TEST(TraceDepthPackingTest, WeightOnePacksToTheFullField) {
    const unsigned int p = TraceDepth::pack(0u, 1u, 1.0f);
    EXPECT_EQ(p >> TraceDepth::WEIGHT_SHIFT, 65535u);
    EXPECT_EQ(TraceDepth::weight(p), 1.0f);
}

TEST(TraceDepthPackingTest, WeightZeroPacksToAnEmptyField) {
    const unsigned int p = TraceDepth::pack(3u, 4u, 0.0f);
    EXPECT_EQ(p >> TraceDepth::WEIGHT_SHIFT, 0u);
    EXPECT_EQ(TraceDepth::weight(p), 0.0f);
    EXPECT_EQ(TraceDepth::bounce(p), 3u);
    EXPECT_EQ(TraceDepth::nesting(p), 4u);
}

TEST(TraceDepthPackingTest, PrimaryHasNoBouncesOneTraceAndFullWeight) {
    const unsigned int p = TraceDepth::primary();
    EXPECT_EQ(TraceDepth::bounce(p), 0u);
    EXPECT_EQ(TraceDepth::nesting(p), 1u);
    EXPECT_EQ(TraceDepth::weight(p), 1.0f);
}

TEST(TraceDepthPackingTest, ChildMultipliesTheWeightAndNestsOneDeeper) {
    const unsigned int parent = TraceDepth::pack(2u, 3u, 0.5f);
    const unsigned int child = TraceDepth::childOf(parent, 3u, 0.5f);
    EXPECT_EQ(TraceDepth::bounce(child), 3u);
    EXPECT_EQ(TraceDepth::nesting(child), 4u);
    EXPECT_NEAR(TraceDepth::weight(child), 0.25f, 2.0f * kWeightStep);
}

TEST(TraceDepthPackingTest, ChildWithFactorOneKeepsTheWeight) {
    const unsigned int parent = TraceDepth::pack(0u, 1u, 0.3f);
    const unsigned int child = TraceDepth::childOf(parent, 0u, 1.0f);
    EXPECT_EQ(TraceDepth::weight(child), TraceDepth::weight(parent));
}

TEST(TraceDepthPackingTest, ChildClampsTheFactorToUnitRange) {
    const unsigned int parent = TraceDepth::pack(0u, 1u, 0.5f);
    EXPECT_EQ(TraceDepth::weight(TraceDepth::childOf(parent, 0u, 7.0f)),
              TraceDepth::weight(parent));
    EXPECT_EQ(TraceDepth::weight(TraceDepth::childOf(parent, 0u, -1.0f)), 0.0f);
}

TEST(TraceDepthPackingTest, TheDeepestPipelineNestingDoesNotSpillIntoTheWeightBits) {
    // PIPELINE_MAX_TRACE_DEPTH is 31 (OptiXData.h); even the full 8-bit nesting field stays put.
    for (unsigned int nest : {31u, 255u}) {
        const unsigned int p = TraceDepth::pack(5u, nest, 0.5f);
        EXPECT_EQ(TraceDepth::nesting(p), nest);
        EXPECT_EQ(TraceDepth::bounce(p), 5u);
        EXPECT_NEAR(TraceDepth::weight(p), 0.5f, kWeightStep);
    }
}

TEST(TraceDepthPackingTest, ABounceDepthBeyondItsFieldDoesNotCorruptTheNeighbours) {
    const unsigned int p = TraceDepth::pack(256u + 5u, 2u, 0.5f);
    EXPECT_EQ(TraceDepth::nesting(p), 2u);
    EXPECT_NEAR(TraceDepth::weight(p), 0.5f, kWeightStep);
}
