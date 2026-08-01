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

// Writes this instance's colour into the radiance payload. If the SPI supplied
// per-instance custom data (Task 1.1c), the colour is read from it (blob = 3
// bytes R,G,B); otherwise a fixed magenta. This also exercises that a REGISTRANT
// module reads `params` correctly — the path menger-geometry's 4D shaders need.
extern "C" __global__ void __closesthit__spi_stub() {
    unsigned int r = 230u, g = 40u, b = 200u;  // default magenta
    if (params.custom_geometry_data != nullptr && params.instance_materials != nullptr) {
        const unsigned int id = optixGetInstanceId();
        const int idx = params.instance_materials[id].geometry_data_index;
        if (idx >= 0) {
            const unsigned char* blob =
                reinterpret_cast<const unsigned char*>(params.custom_geometry_data)
                + static_cast<size_t>(idx) * params.custom_geometry_stride;
            r = blob[0]; g = blob[1]; b = blob[2];
        }
    }
    optixSetPayload_0(r);
    optixSetPayload_1(g);
    optixSetPayload_2(b);
}

// Alternate closest hit that reads its colour from the InstanceMaterial set via
// setInstanceMaterial (Task 1.2b) — the same params.instance_materials[id] path the
// real 4D shaders reach through getInstanceMaterialPBR. Proves a registrant's shader
// sees materials assigned generically, without a per-instance blob.
extern "C" __global__ void __closesthit__spi_material() {
    unsigned int r = 230u, g = 40u, b = 200u;  // default magenta
    if (params.instance_materials != nullptr) {
        const unsigned int id = optixGetInstanceId();
        const float* c = params.instance_materials[id].color;
        r = static_cast<unsigned int>(__saturatef(c[0]) * 255.0f + 0.5f);
        g = static_cast<unsigned int>(__saturatef(c[1]) * 255.0f + 0.5f);
        b = static_cast<unsigned int>(__saturatef(c[2]) * 255.0f + 0.5f);
    }
    optixSetPayload_0(r);
    optixSetPayload_1(g);
    optixSetPayload_2(b);
}
