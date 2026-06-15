// project4d.cu — 4D rotation + perspective projection compute kernel.
//
// Sprint 18.3 Cut A: replaces the CPU-side `Mesh4DProjection.toTriangleMesh`
// pipeline with a parallel CUDA kernel. Generalized in Sprint 19.2 to support
// variable vertices-per-face (3=tri, 4=quad, 5=pentagon).
//
// Input: N faces × V corners × float4 (x,y,z,w)
// Output: V vertices × 8-stride (pos·3 + normal·3 + uv·2) + fan indices

#define MAX_VERTS_PER_FACE 8

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

}  // namespace

extern "C" __global__ void project4d_faces_kernel(
    const float4* __restrict__ in_faces_4d,
    const float2* __restrict__ in_uvs,
    int num_faces,
    Projection4DParams proj,
    float* __restrict__ out_vertices_3d,
    unsigned int* __restrict__ out_indices
) {
    int q = blockIdx.x * blockDim.x + threadIdx.x;
    if (q >= num_faces) return;

    unsigned int V = proj.verts_per_face;
    if (V < 3 || V > MAX_VERTS_PER_FACE) return;

    int corner_base = q * V;
    float4 v4[MAX_VERTS_PER_FACE];
    float3 v3[MAX_VERTS_PER_FACE];

    for (unsigned int c = 0; c < V; ++c) {
        float4 raw = in_faces_4d[corner_base + c];
        v4[c] = mul_mat4_vec4(proj.rotation, raw);
        float3 p3 = perspective_w(v4[c], proj.eye_w, proj.screen_w);
        p3.x += proj.center_x;
        p3.y += proj.center_y;
        p3.z += proj.center_z;
        v3[c] = p3;
    }

    // Face normal via Newell's method (robust for any n-gon)
    float nx = 0.0f, ny = 0.0f, nz = 0.0f;
    for (unsigned int c = 0; c < V; ++c) {
        unsigned int nxt = (c + 1 < V) ? (c + 1) : 0;
        nx += (v3[c].y - v3[nxt].y) * (v3[c].z + v3[nxt].z);
        ny += (v3[c].z - v3[nxt].z) * (v3[c].x + v3[nxt].x);
        nz += (v3[c].x - v3[nxt].x) * (v3[c].y + v3[nxt].y);
    }
    float n_len2 = nx * nx + ny * ny + nz * nz;
    if (n_len2 < 0.0001f) {
        nx = 0.0f; ny = 1.0f; nz = 0.0f;
    } else {
        float inv = rsqrtf(n_len2);
        nx *= inv; ny *= inv; nz *= inv;
    }

    // Write V vertices × 8-stride
    int vbase = q * V * 8;
    for (unsigned int c = 0; c < V; ++c) {
        float u, v;
        if (in_uvs != nullptr) {
            float2 uv = in_uvs[corner_base + c];
            u = uv.x; v = uv.y;
        } else {
            // Simple UVs: angle-based distribution around center
            float angle = (float)c / (float)V * 6.283185307f;
            u = 0.5f + 0.5f * cosf(angle);
            v = 0.5f + 0.5f * sinf(angle);
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

    // Fan triangulation: for k=2..V-1, emit triangle [0, k-1, k]
    unsigned int tri_count = V - 2;
    int ibase = q * tri_count * 3;
    int vert0 = corner_base;
    for (unsigned int k = 2; k < V; ++k) {
        unsigned int ti = k - 2;
        out_indices[ibase + ti * 3 + 0] = vert0 + 0;
        out_indices[ibase + ti * 3 + 1] = vert0 + k - 1;
        out_indices[ibase + ti * 3 + 2] = vert0 + k;
    }
}

// Backward-compatible host launcher (same C-linkage name, generalized params)
extern "C" cudaError_t launchProject4DQuadsKernel(
    const void* d_faces_4d,
    const void* d_uvs_or_null,
    int num_faces,
    const Projection4DParams* params,
    void* d_vertices_3d,
    void* d_indices,
    cudaStream_t stream
) {
    if (num_faces <= 0) return cudaSuccess;
    if (params->verts_per_face < 3 || params->verts_per_face > MAX_VERTS_PER_FACE)
        return cudaErrorInvalidValue;
    int block = 256;
    int grid = (num_faces + block - 1) / block;
    project4d_faces_kernel<<<grid, block, 0, stream>>>(
        reinterpret_cast<const float4*>(d_faces_4d),
        reinterpret_cast<const float2*>(d_uvs_or_null),
        num_faces,
        *params,
        reinterpret_cast<float*>(d_vertices_3d),
        reinterpret_cast<unsigned int*>(d_indices)
    );
    return cudaGetLastError();
}
