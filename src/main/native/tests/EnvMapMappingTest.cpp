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
    const float3 d = envUvToDir(0.3f, 1e-5f);
    EXPECT_NEAR(d.y, 1.0f, 1e-5f);
    EXPECT_NEAR(d.x, 0.0f, kEpsilon);
    EXPECT_NEAR(d.z, 0.0f, kEpsilon);
}

TEST(EnvMapMappingTest, BottomRowIsMinusY) {
    EXPECT_TRUE(DirNear(envUvToDir(0.3f, 1.0f), 0.0f, -1.0f, 0.0f));
}

namespace {
::testing::AssertionResult UvNear(float2 actual, float u, float v) {
    if (std::fabs(actual.x - u) <= kEpsilon && std::fabs(actual.y - v) <= kEpsilon) {
        return ::testing::AssertionSuccess();
    }
    return ::testing::AssertionFailure()
        << "expected (" << u << ", " << v << ") but got (" << actual.x << ", " << actual.y << ")";
}
}  // namespace

TEST(EnvMapMappingTest, DirToUvAxes) {
    EXPECT_TRUE(UvNear(envDirToUv(make_float3(1.f, 0.f, 0.f)), 0.5f, 0.5f));
    EXPECT_TRUE(UvNear(envDirToUv(make_float3(0.f, 0.f, 1.f)), 0.75f, 0.5f));
    EXPECT_TRUE(UvNear(envDirToUv(make_float3(0.f, 0.f, -1.f)), 0.25f, 0.5f));
    EXPECT_TRUE(UvNear(envDirToUv(make_float3(0.f, 1.f, 0.f)), 0.5f, 0.0f));
    EXPECT_TRUE(UvNear(envDirToUv(make_float3(0.f, -1.f, 0.f)), 0.5f, 1.0f));
}

TEST(EnvMapMappingTest, MinusXIsTheSeam) {
    // -x lies on the u = 0 / u = 1 seam; either side is correct.
    const float u = envDirToUv(make_float3(-1.f, 0.f, 0.f)).x;
    EXPECT_TRUE(u < kEpsilon || u > 1.0f - kEpsilon) << "u=" << u;
}

TEST(EnvMapMappingTest, DirToUvClampsYJustAboveOne) {
    // A unit-ish vector whose y rounded just past 1 must not make asin return NaN.
    const float2 uv = envDirToUv(make_float3(0.f, 1.0000001f, 0.f));
    EXPECT_TRUE(std::isfinite(uv.x));
    EXPECT_TRUE(std::isfinite(uv.y));
    EXPECT_NEAR(uv.y, 0.0f, kEpsilon);
}

TEST(EnvMapMappingTest, SinThetaIsOneAtTheEquatorAndZeroAtThePoles) {
    EXPECT_NEAR(envUvSinTheta(0.5f), 1.0f, kEpsilon);
    EXPECT_NEAR(envUvSinTheta(0.0f), 0.0f, kEpsilon);
    EXPECT_NEAR(envUvSinTheta(1.0f), 0.0f, kEpsilon);
}
