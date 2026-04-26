#ifndef PROJECT4D_H
#define PROJECT4D_H

// Host-side declarations for project4d.cu (Sprint 18.3 Cut A).
// The kernel itself lives in project4d.cu; this header exposes the params
// struct and a C-linkage launcher so OptiXWrapper.cpp can drive it.

#include <cuda_runtime.h>

extern "C" {

struct Projection4DParams {
    // 4x4 row-major rotation matrix, pre-composed on the host
    // as R_xw * R_yw * R_zw (matches Rotation.scala line 41).
    float rotation[16];
    float eye_w;
    float screen_w;
    // Center translation applied in 3D after projection
    // (matches Mesh4DProjection.translateMesh).
    float center_x;
    float center_y;
    float center_z;
};

cudaError_t launchProject4DQuadsKernel(
    const void* d_quads_4d,        // length 4*num_quads, float4 per corner
    const void* d_uvs_or_null,     // length 4*num_quads float2, or nullptr
    int num_quads,
    const Projection4DParams* params,
    void* d_vertices_3d,            // length 8 * 4 * num_quads floats
    void* d_indices,                // length 6 * num_quads unsigned ints
    cudaStream_t stream
);

}  // extern "C"

#endif  // PROJECT4D_H
