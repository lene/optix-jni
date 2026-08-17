#include <cmath>

#include <gtest/gtest.h>
#include "VectorMath.h"

// reflect() is __host__ __device__ (VectorMath.h) so it compiles and runs here as plain host
// C++ — no CUDA runtime, no OptiX context, no full render. It is the single implementation
// shared by helpers.cu's traceReflectedRay and traceFinalNonRecursiveRay; before Sprint 36
// H3.2, the latter had its own independent, buggy reimplementation (fabsf(dot(...)) instead
// of the signed dot product), which degenerated to a near-identity "reflection" — the visible
// symptom was a metallic surface behaving as if it were transparent. These tests pin the
// shared formula directly, closer to the source than any render-level regression test can get.

namespace {
constexpr float kEpsilon = 1e-5f;

::testing::AssertionResult Float3Near(float3 actual, float3 expected, float eps = kEpsilon) {
    if (std::fabs(actual.x - expected.x) <= eps &&
        std::fabs(actual.y - expected.y) <= eps &&
        std::fabs(actual.z - expected.z) <= eps) {
        return ::testing::AssertionSuccess();
    }
    return ::testing::AssertionFailure()
        << "expected (" << expected.x << ", " << expected.y << ", " << expected.z << ") "
        << "but got (" << actual.x << ", " << actual.y << ", " << actual.z << ")";
}
}  // namespace

TEST(ReflectTest, HeadOnHitBouncesStraightBack) {
    // Ray travels +z, hits a surface facing the ray (normal points back at -z).
    const float3 incident = make_float3(0.0f, 0.0f, 1.0f);
    const float3 normal = make_float3(0.0f, 0.0f, -1.0f);
    EXPECT_TRUE(Float3Near(reflect(incident, normal), make_float3(0.0f, 0.0f, -1.0f)));
}

TEST(ReflectTest, FortyFiveDegreeIncidenceMirrorsAngle) {
    // Classic angle-of-incidence == angle-of-reflection case: a ray hitting a horizontal
    // floor (normal +y) at 45 degrees reflects to the mirrored 45-degree direction.
    const float s = 1.0f / std::sqrt(2.0f);
    const float3 incident = make_float3(s, -s, 0.0f);
    const float3 normal = make_float3(0.0f, 1.0f, 0.0f);
    EXPECT_TRUE(Float3Near(reflect(incident, normal), make_float3(s, s, 0.0f)));
}

TEST(ReflectTest, GrazingIncidenceLeavesDirectionNearlyUnchanged) {
    // A ray nearly parallel to the surface (dot(I,N) close to zero) should barely bend —
    // this is the legitimate near-identity case, unlike the head-on-hit bug below.
    const float3 incident = normalize(make_float3(1.0f, 0.001f, 0.0f));
    const float3 normal = make_float3(0.0f, 1.0f, 0.0f);
    const float3 result = reflect(incident, normal);
    EXPECT_NEAR(dot(normalize(result), incident), 1.0f, 1e-3f);
}

TEST(ReflectTest, PreservesVectorLength) {
    const float3 incident = normalize(make_float3(0.3f, -0.7f, 0.5f));
    const float3 normal = normalize(make_float3(0.1f, 0.9f, -0.2f));
    EXPECT_NEAR(length(reflect(incident, normal)), length(incident), kEpsilon);
}

TEST(ReflectTest, IsAnInvolution) {
    // Reflecting twice about the same normal returns the original direction.
    const float3 incident = normalize(make_float3(0.4f, -0.6f, 0.8f));
    const float3 normal = normalize(make_float3(-0.2f, 0.5f, 0.3f));
    const float3 twice = reflect(reflect(incident, normal), normal);
    EXPECT_TRUE(Float3Near(twice, incident, 1e-4f));
}

// Sprint 36 H3.2 regression: the buggy formula was
// R_buggy = I - 2*fabsf(dot(I,N))*N = I + 2*dot(I,N)*N  (sign of the correction term flipped)
// which for a head-on hit reduces to R_buggy proportional to I itself — the ray continues
// in its original direction instead of bouncing back, i.e. the surface reads as transparent.
TEST(ReflectTest, HeadOnHitDoesNotDegenerateToPassThrough) {
    const float3 incident = make_float3(0.0f, 0.0f, 1.0f);
    const float3 normal = make_float3(0.0f, 0.0f, -1.0f);
    const float3 result = reflect(incident, normal);

    // The historical bug's result, for comparison: same *direction* as the incident ray
    // (magnitude differs — the buggy formula isn't unit-preserving — but it points forward,
    // straight through the surface, instead of back).
    const float3 buggy_result = incident - 2.0f * std::fabs(dot(incident, normal)) * normal;
    EXPECT_NEAR(dot(normalize(buggy_result), incident), 1.0f, kEpsilon)
        << "sanity check on the reconstructed buggy formula itself";

    // The correct result must be the opposite of that — reflected back, not passed through.
    EXPECT_LT(dot(normalize(result), incident), 0.0f);
}
