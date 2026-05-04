#ifndef PROJECT4D_H
#define PROJECT4D_H

// Host-side declarations for project4d.cu (Sprint 18.3 Cut A).
// The kernel itself lives in project4d.cu; this header exposes the params
// struct and a C-linkage launcher so OptiXWrapper.cpp can drive it.

#include <cuda_runtime.h>

extern "C" {

struct Projection4DParams {
    float rotation[16];
    float eye_w;
    float screen_w;
    float center_x;
    float center_y;
    float center_z;
    unsigned int verts_per_face;   // 3=tri, 4=quad, 5=pentagon
};

cudaError_t launchProject4DQuadsKernel(
    const void* d_faces_4d,        // length verts_per_face * num_faces, float4 per corner
    const void* d_uvs_or_null,     // length verts_per_face * num_faces float2, or nullptr
    int num_faces,
    const Projection4DParams* params,
    void* d_vertices_3d,            // length 8 * verts_per_face * num_faces floats
    void* d_indices,                // length 3 * (verts_per_face-2) * num_faces unsigned ints
    cudaStream_t stream
);

}  // extern "C"

#endif  // PROJECT4D_H
