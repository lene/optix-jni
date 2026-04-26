// project4d.cu — 4D rotation + perspective projection compute kernel.
//
// Sprint 18.3 Cut A: replaces the CPU-side `Mesh4DProjection.toTriangleMesh`
// pipeline (rotation + perspective divide + per-quad normal) with a parallel
// CUDA kernel that runs once at mesh upload (and again per-frame when Cut F's
// updateMesh4DProjection is called).
//
// Layout matches Mesh4DProjection.scala:
//   - Input: N quads × 4 corners × float4 (x,y,z,w)
//   - Optional UVs: N quads × 4 corners × float2 (u,v); when null we emit the
//     same default unit-square UVs as the CPU code (0,0)(1,0)(1,1)(0,1)
//   - Output: 8-float-stride vertex array (pos·3 + normal·3 + uv·2),
//     6 indices per quad ([0,1,2, 0,2,3] offset by 4*q)

#include "Project4D.h"

namespace {

__device__ inline float4 mul_mat4_vec4(const float* M, float4 v) {
    return make_float4(
        M[0]  * v.x + M[1]  * v.y + M[2]  * v.z + M[3]  * v.w,
        M[4]  * v.x + M[5]  * v.y + M[6]  * v.z + M[7]  * v.w,
        M[8]  * v.x + M[9]  * v.y + M[10] * v.z + M[11] * v.w,
        M[12] * v.x + M[13] * v.y + M[14] * v.z + M[15] * v.w
    );
}

__device__ inline float3 perspective_w(float4 p, float eye_w, float screen_w) {
    float f = (eye_w - screen_w) / (eye_w - p.w);
    return make_float3(p.x * f, p.y * f, p.z * f);
}

__device__ inline float3 sub3(float3 a, float3 b) {
    return make_float3(a.x - b.x, a.y - b.y, a.z - b.z);
}

__device__ inline float3 cross3(float3 a, float3 b) {
    return make_float3(
        a.y * b.z - a.z * b.y,
        a.z * b.x - a.x * b.z,
        a.x * b.y - a.y * b.x
    );
}

}  // namespace

// One CUDA thread per quad.
extern "C" __global__ void project4d_quads_kernel(
    const float4* __restrict__ in_quads_4d,    // length 4*N
    const float2* __restrict__ in_uvs,         // length 4*N, may be null
    int num_quads,
    Projection4DParams proj,
    float* __restrict__ out_vertices_3d,        // length 8 * 4 * N
    unsigned int* __restrict__ out_indices       // length 6 * N
) {
    int q = blockIdx.x * blockDim.x + threadIdx.x;
    if (q >= num_quads) return;

    int corner_base = q * 4;
    float4 v4[4];
    float3 v3[4];

    #pragma unroll
    for (int c = 0; c < 4; ++c) {
        float4 raw = in_quads_4d[corner_base + c];
        v4[c] = mul_mat4_vec4(proj.rotation, raw);
        float3 p3 = perspective_w(v4[c], proj.eye_w, proj.screen_w);
        // Apply center translation (matches CPU translateMesh).
        p3.x += proj.center_x;
        p3.y += proj.center_y;
        p3.z += proj.center_z;
        v3[c] = p3;
    }

    // Face normal via cross product of edges (v1-v0) x (v3-v0), with
    // the same degenerate-face fallback as Mesh4DProjection.scala:67.
    float3 edge1 = sub3(v3[1], v3[0]);
    float3 edge2 = sub3(v3[3], v3[0]);
    float3 n = cross3(edge1, edge2);
    float n_len2 = n.x * n.x + n.y * n.y + n.z * n.z;
    float nx, ny, nz;
    if (n_len2 < 0.0001f) {
        nx = 0.0f; ny = 1.0f; nz = 0.0f;
    } else {
        float inv = rsqrtf(n_len2);
        nx = n.x * inv; ny = n.y * inv; nz = n.z * inv;
    }

    // Default UVs match Mesh4DProjection.scala:72-75:
    //   v0 = (0,0), v1 = (1,0), v2 = (1,1), v3 = (0,1).
    float default_uv[4][2] = {
        {0.0f, 0.0f}, {1.0f, 0.0f}, {1.0f, 1.0f}, {0.0f, 1.0f}
    };

    int vbase = q * 4 * 8;
    #pragma unroll
    for (int c = 0; c < 4; ++c) {
        float u, v;
        if (in_uvs != nullptr) {
            float2 uv = in_uvs[corner_base + c];
            u = uv.x; v = uv.y;
        } else {
            u = default_uv[c][0];
            v = default_uv[c][1];
        }
        int o = vbase + c * 8;
        out_vertices_3d[o + 0] = v3[c].x;
        out_vertices_3d[o + 1] = v3[c].y;
        out_vertices_3d[o + 2] = v3[c].z;
        out_vertices_3d[o + 3] = nx;
        out_vertices_3d[o + 4] = ny;
        out_vertices_3d[o + 5] = nz;
        out_vertices_3d[o + 6] = u;
        out_vertices_3d[o + 7] = v;
    }

    int ibase = q * 6;
    int corner0 = corner_base;  // 4*q
    out_indices[ibase + 0] = corner0 + 0;
    out_indices[ibase + 1] = corner0 + 1;
    out_indices[ibase + 2] = corner0 + 2;
    out_indices[ibase + 3] = corner0 + 0;
    out_indices[ibase + 4] = corner0 + 2;
    out_indices[ibase + 5] = corner0 + 3;
}

// Host-side launcher with C linkage so it can be called from OptiXWrapper.cpp
// without dragging the kernel signature into a C++ header.
extern "C" cudaError_t launchProject4DQuadsKernel(
    const void* d_quads_4d,
    const void* d_uvs_or_null,
    int num_quads,
    const Projection4DParams* params,
    void* d_vertices_3d,
    void* d_indices,
    cudaStream_t stream
) {
    if (num_quads <= 0) return cudaSuccess;
    int block = 256;
    int grid = (num_quads + block - 1) / block;
    project4d_quads_kernel<<<grid, block, 0, stream>>>(
        reinterpret_cast<const float4*>(d_quads_4d),
        reinterpret_cast<const float2*>(d_uvs_or_null),
        num_quads,
        *params,
        reinterpret_cast<float*>(d_vertices_3d),
        reinterpret_cast<unsigned int*>(d_indices)
    );
    return cudaGetLastError();
}
