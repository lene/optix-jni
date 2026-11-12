#include <cmath>

#include <gtest/gtest.h>
#include "EnvMapMapping.h"

// The background and IBL share envDirToUv / envUvToDir (optix-jni#112). Before the fix IBL
// used phi = 2*pi*u, so a bright texel lit the scene from the opposite azimuth of where the
// background showed it. These tests pin the two as exact inverses and fix the convention.

namespace {
constexpr float kEpsilon = 1e-4f;

::testing::AssertionResult DirNear(float3 actual, float x, float y, float z) {
    if (std::fabs(actual.x - x) <= kEpsilon && std::fabs(actual.y - y) <= kEpsilon &&
        std::fabs(actual.z - z) <= kEpsilon) {
        return ::testing::AssertionSuccess();
    }
    return ::testing::AssertionFailure()
        << "expected (" << x << ", " << y << ", " << z << ") but got ("
        << actual.x << ", " << actual.y << ", " << actual.z << ")";
}
}  // namespace

TEST(EnvMapMappingTest, UvToDirAndBackRoundTrips) {
    for (int i = 0; i < 16; ++i) {
        for (int j = 0; j < 8; ++j) {
            const float u = 0.05f + 0.9f * static_cast<float>(i) / 15.0f;
            const float v = 0.1f + 0.8f * static_cast<float>(j) / 7.0f;
            const float2 back = envDirToUv(envUvToDir(u, v));
            EXPECT_NEAR(back.x, u, kEpsilon) << "u=" << u << " v=" << v;
            EXPECT_NEAR(back.y, v, kEpsilon) << "u=" << u << " v=" << v;
        }
    }
}

TEST(EnvMapMappingTest, MapCentreIsPlusX) {
    EXPECT_TRUE(DirNear(envUvToDir(0.5f, 0.5f), 1.0f, 0.0f, 0.0f));
}

TEST(EnvMapMappingTest, ThreeQuartersIsPlusZ) {
    EXPECT_TRUE(DirNear(envUvToDir(0.75f, 0.5f), 0.0f, 0.0f, 1.0f));
}

TEST(EnvMapMappingTest, OneQuarterIsMinusZ) {
    EXPECT_TRUE(DirNear(envUvToDir(0.25f, 0.5f), 0.0f, 0.0f, -1.0f));
}

TEST(EnvMapMappingTest, TopRowApproachesPlusY) {
    const float3 d = envUvToDir(0.3f, 1e-4f);
    EXPECT_NEAR(d.y, 1.0f, 1e-5f);
}
