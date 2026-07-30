//==============================================================================
// Custom-geometry SPI test fixture (Sprint 35 Task 1.1d).
//
// A deliberately minimal external primitive used ONLY by CustomGeometrySpiSuite
// to regression-test the managed-pipeline SPI (registration -> runtime type id ->
// dynamic program group -> dynamic SBT slot -> sbtOffset dispatch). It is a
// standalone module (spi_test_stub.ptx), separate from optix_shaders.ptx.
//
// It is intentionally params-INDEPENDENT: the intersection uses only the OptiX
// object-space ray, and the closest hit writes a fixed magenta straight into the
// radiance payload (slots 0,1,2 = R,G,B bytes, per raygen_primary.cu). That
// isolates the SPI plumbing under test from any launch-params / material plumbing,
// so a magenta silhouette where the instance sits proves dispatch reached the
// registered shader — nothing else.
//==============================================================================

#include <optix.h>
#include "../include/OptiXData.h"
#include "../include/VectorMath.h"

// Declared to match the pipeline's launch-params binding; intentionally unused.
extern "C" {
    __constant__ BaseParams params;
}

// Object-space unit sphere (radius 0.5) at the origin.
extern "C" __global__ void __intersection__spi_stub() {
    const float3 orig = optixGetObjectRayOrigin();
    const float3 dir  = optixGetObjectRayDirection();
    const float radius = 0.5f;
    const float b = dot(orig, dir);
    const float c = dot(orig, orig) - radius * radius;
    const float disc = b * b - c;
    if (disc < 0.0f) return;
    const float sq = sqrtf(disc);
    const float tmin = optixGetRayTmin();
    const float tmax = optixGetRayTmax();
    float t = -b - sq;
    if (t < tmin) t = -b + sq;
    if (t < tmin || t > tmax) return;
    optixReportIntersection(t, 0u);
}

// Flat magenta into the radiance payload — no params, no material.
extern "C" __global__ void __closesthit__spi_stub() {
    optixSetPayload_0(230u);  // R
    optixSetPayload_1(40u);   // G
    optixSetPayload_2(200u);  // B
}
