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

// The IBL sun exclusion: one cone test for the host that builds the sampling tables without the
// excluded texels and the device that returns no radiance for them.
namespace {
constexpr int kMapWidth = 64;
constexpr int kMapHeight = 32;

// The texel of a 64 x 32 map whose centre direction is nearest `d`.
int2 texelOf(float3 d) {
    const float2 uv = envDirToUv(d);
    return make_int2(static_cast<int>(uv.x * kMapWidth), static_cast<int>(uv.y * kMapHeight));
}

float cosOfDegrees(float degrees) { return std::cos(degrees * 3.14159265358979f / 180.0f); }
}  // namespace

TEST(EnvTexelInConeTest, ATexelWhoseCentreIsTheAxisIsInsideAnyCone) {
    const float3 axis = envUvToDir((38 + 0.5f) / kMapWidth, (12 + 0.5f) / kMapHeight);
    EXPECT_TRUE(envTexelInCone(38, 12, kMapWidth, kMapHeight, axis, cosOfDegrees(0.1f)));
}

TEST(EnvTexelInConeTest, TheOppositeTexelIsOutsideEveryConeUpToAHemisphere) {
    const float3 axis = envUvToDir(0.6f, 0.4f);
    const float3 opposite = make_float3(-axis.x, -axis.y, -axis.z);
    const int2 texel = texelOf(opposite);
    EXPECT_FALSE(envTexelInCone(texel.x, texel.y, kMapWidth, kMapHeight, axis, cosOfDegrees(90.0f)));
}

TEST(EnvTexelInConeTest, TheConeEdgeFollowsTheHalfAngle) {
    // Near the equator texel centres are 360/64 = 5.625 degrees apart. With the axis on a texel
    // centre of row 16 (elevation 2.8 degrees, so the neighbour is 5.62 degrees away), the
    // neighbour is inside a 6 degree cone and outside a 5 degree one.
    const float3 axis = envUvToDir((10 + 0.5f) / kMapWidth, (16 + 0.5f) / kMapHeight);
    const int2 own = texelOf(axis);
    EXPECT_TRUE(envTexelInCone(own.x, own.y, kMapWidth, kMapHeight, axis, cosOfDegrees(5.0f)));
    EXPECT_FALSE(envTexelInCone(own.x + 1, own.y, kMapWidth, kMapHeight, axis, cosOfDegrees(5.0f)));
    EXPECT_TRUE(envTexelInCone(own.x + 1, own.y, kMapWidth, kMapHeight, axis, cosOfDegrees(6.0f)));
}

TEST(EnvTexelInConeTest, ACosineAboveOneSelectsNothing) {
    const float3 axis = envUvToDir((38 + 0.5f) / kMapWidth, (12 + 0.5f) / kMapHeight);
    EXPECT_FALSE(envTexelInCone(38, 12, kMapWidth, kMapHeight, axis, 2.0f));
}

TEST(EnvTexelInConeTest, TheConeCrossesTheSeamAtMinusX) {
    // -x is the u = 0 / u = 1 seam: a cone around it holds texels from both edges of the map.
    const float3 axis = make_float3(-1.0f, 0.0f, 0.0f);
    const int mid = kMapHeight / 2;
    EXPECT_TRUE(envTexelInCone(0, mid, kMapWidth, kMapHeight, axis, cosOfDegrees(5.0f)));
    EXPECT_TRUE(envTexelInCone(kMapWidth - 1, mid, kMapWidth, kMapHeight, axis, cosOfDegrees(5.0f)));
}
