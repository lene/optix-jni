//==============================================================================
// 4D Menger Sponge Analog — IFS Intersection Shader
//
// Renders the 4D Menger sponge analog via iterative IFS traversal on the GPU.
// O(1) VRAM: no geometry stored — each ray traverses the IFS stack per-thread.
//
// Algorithm:
//   Maintain a stack of (4D-offset, half-extent, depth) nodes.
//   At each node, project the 4D bounding hypercube to a 3D AABB; fast-reject
//   rays that miss the AABB. At leaf depth, project all 16 vertices of the 4D
//   cell and ray-triangle test against the 24 quad faces (48 triangles) of the
//   projected hypercube — matching TesseractSponge geometry exactly.
//
// Vertex ordering: vertex i (0-15) of 4D cell at (ox,oy,oz,ow) ± half:
//   x = ox + ((i&8) ? +half : -half)   (bit 3 = x-axis, varies slowest)
//   y = oy + ((i&4) ? +half : -half)   (bit 2 = y-axis)
//   z = oz + ((i&2) ? +half : -half)   (bit 1 = z-axis)
//   w = ow + ((i&1) ? +half : -half)   (bit 0 = w-axis, varies fastest)
// Matches Tesseract.scala: `for (xx <- coords; yy <- coords; zz <- coords; ww <- coords)`
//
// Face table: 24 quads from Tesseract.scala faceIndices, each split into 2 triangles.
// This file is included by optix_shaders.cu — do not compile separately.
//==============================================================================

// ---- 48 IFS generators (threshold=2) ----
// g = (xx,yy,zz,ww) for all (xx,yy,zz,ww) in {-1,0,1}^4 with abs-sum > 2
static __constant__ float menger4d_generators[48][4] = {
    // abs-sum == 3 (32 entries)
    {-1,-1,-1, 0},{-1,-1, 1, 0},{-1, 1,-1, 0},{-1, 1, 1, 0},
    { 1,-1,-1, 0},{ 1,-1, 1, 0},{ 1, 1,-1, 0},{ 1, 1, 1, 0},
    {-1,-1, 0,-1},{-1,-1, 0, 1},{-1, 1, 0,-1},{-1, 1, 0, 1},
    { 1,-1, 0,-1},{ 1,-1, 0, 1},{ 1, 1, 0,-1},{ 1, 1, 0, 1},
    {-1, 0,-1,-1},{-1, 0,-1, 1},{-1, 0, 1,-1},{-1, 0, 1, 1},
    { 1, 0,-1,-1},{ 1, 0,-1, 1},{ 1, 0, 1,-1},{ 1, 0, 1, 1},
    { 0,-1,-1,-1},{ 0,-1,-1, 1},{ 0,-1, 1,-1},{ 0,-1, 1, 1},
    { 0, 1,-1,-1},{ 0, 1,-1, 1},{ 0, 1, 1,-1},{ 0, 1, 1, 1},
    // abs-sum == 4 (16 entries)
    {-1,-1,-1,-1},{-1,-1,-1, 1},{-1,-1, 1,-1},{-1,-1, 1, 1},
    {-1, 1,-1,-1},{-1, 1,-1, 1},{-1, 1, 1,-1},{-1, 1, 1, 1},
    { 1,-1,-1,-1},{ 1,-1,-1, 1},{ 1,-1, 1,-1},{ 1,-1, 1, 1},
    { 1, 1,-1,-1},{ 1, 1,-1, 1},{ 1, 1, 1,-1},{ 1, 1, 1, 1},
};
static __constant__ int menger4d_generator_abssum[48] = {
    3,3,3,3,3,3,3,3, 3,3,3,3,3,3,3,3,
    3,3,3,3,3,3,3,3, 3,3,3,3,3,3,3,3,
    4,4,4,4,4,4,4,4, 4,4,4,4,4,4,4,4,
};
static __constant__ int menger4d_generator_count = 48;

// ---- 24 quad faces of a unit tesseract, split into 48 triangles ----
// From Tesseract.scala faceIndices. Vertex ordering per header above.
static __device__ __constant__ int MENGER4D_FACE_TRIS[48][3] = {
    {0,1,3},{0,3,2},       // quad (0,1,3,2)
    {0,1,5},{0,5,4},       // quad (0,1,5,4)
    {0,1,9},{0,9,8},       // quad (0,1,9,8)
    {0,2,6},{0,6,4},       // quad (0,2,6,4)
    {0,2,10},{0,10,8},     // quad (0,2,10,8)
    {0,4,12},{0,12,8},     // quad (0,4,12,8)
    {3,1,5},{3,5,7},       // quad (3,1,5,7)
    {3,1,9},{3,9,11},      // quad (3,1,9,11)
    {5,1,9},{5,9,13},      // quad (5,1,9,13)
    {3,2,6},{3,6,7},       // quad (3,2,6,7)
    {3,2,10},{3,10,11},    // quad (3,2,10,11)
    {10,2,6},{10,6,14},    // quad (10,2,6,14)
    {15,7,3},{15,3,11},    // quad (15,7,3,11)
    {5,4,6},{5,6,7},       // quad (5,4,6,7)
    {12,4,5},{12,5,13},    // quad (12,4,5,13)
    {12,4,6},{12,6,14},    // quad (12,4,6,14)
    {15,7,5},{15,5,13},    // quad (15,7,5,13)
    {15,7,6},{15,6,14},    // quad (15,7,6,14)
    {10,8,9},{10,9,11},    // quad (10,8,9,11)
    {12,8,9},{12,9,13},    // quad (12,8,9,13)
    {12,8,10},{12,10,14},  // quad (12,8,10,14)
    {15,11,9},{15,9,13},   // quad (15,11,9,13)
    {15,11,10},{15,10,14}, // quad (15,11,10,14)
    {15,13,12},{15,12,14}  // quad (15,13,12,14)
};

// ---- Math helpers ----

__device__ inline float4 mul4x4_vec4(const float* __restrict__ M, float4 v) {
    return make_float4(
        M[ 0]*v.x + M[ 1]*v.y + M[ 2]*v.z + M[ 3]*v.w,
        M[ 4]*v.x + M[ 5]*v.y + M[ 6]*v.z + M[ 7]*v.w,
        M[ 8]*v.x + M[ 9]*v.y + M[10]*v.z + M[11]*v.w,
        M[12]*v.x + M[13]*v.y + M[14]*v.z + M[15]*v.w
    );
}

__device__ inline float3 persp_w(float4 p, float eye_w, float screen_w) {
    float f = (eye_w - screen_w) / (eye_w - p.w);
    return make_float3(p.x * f, p.y * f, p.z * f);
}

__device__ inline float3 fmin3f(float3 a, float3 b) {
    return make_float3(fminf(a.x,b.x), fminf(a.y,b.y), fminf(a.z,b.z));
}
__device__ inline float3 fmax3f(float3 a, float3 b) {
    return make_float3(fmaxf(a.x,b.x), fmaxf(a.y,b.y), fmaxf(a.z,b.z));
}
__device__ inline float dot3f(float3 a, float3 b) {
    return a.x*b.x + a.y*b.y + a.z*b.z;
}
__device__ inline float3 cross3f(float3 a, float3 b) {
    return make_float3(a.y*b.z - a.z*b.y, a.z*b.x - a.x*b.z, a.x*b.y - a.y*b.x);
}

// Quad vertex order for the 24 faces (matches Tesseract.scala faceIndices).
// Used for Newell's method to compute per-face normals consistent with Mesh4DProjection.
static __device__ __constant__ int MENGER4D_QUADS[24][4] = {
    {0,1,3,2},  {0,1,5,4},  {0,1,9,8},  {0,2,6,4},
    {0,2,10,8}, {0,4,12,8}, {3,1,5,7},  {3,1,9,11},
    {5,1,9,13}, {3,2,6,7},  {3,2,10,11},{10,2,6,14},
    {15,7,3,11},{5,4,6,7},  {12,4,5,13},{12,4,6,14},
    {15,7,5,13},{15,7,6,14},{10,8,9,11},{12,8,9,13},
    {12,8,10,14},{15,11,9,13},{15,11,10,14},{15,13,12,14}
};

// Newell's method polygon normal — matches faceToTriangleMesh in Mesh4DProjection.scala.
__device__ inline float3 newell_normal_quad(float3 p0, float3 p1, float3 p2, float3 p3) {
    float3 n;
    n.x = (p0.y-p1.y)*(p0.z+p1.z) + (p1.y-p2.y)*(p1.z+p2.z)
        + (p2.y-p3.y)*(p2.z+p3.z) + (p3.y-p0.y)*(p3.z+p0.z);
    n.y = (p0.z-p1.z)*(p0.x+p1.x) + (p1.z-p2.z)*(p1.x+p2.x)
        + (p2.z-p3.z)*(p2.x+p3.x) + (p3.z-p0.z)*(p3.x+p0.x);
    n.z = (p0.x-p1.x)*(p0.y+p1.y) + (p1.x-p2.x)*(p1.y+p2.y)
        + (p2.x-p3.x)*(p2.y+p3.y) + (p3.x-p0.x)*(p3.y+p0.y);
    float len = sqrtf(dot3f(n, n));
    if (len < 1e-10f) return make_float3(0.f, 1.f, 0.f);
    return make_float3(n.x/len, n.y/len, n.z/len);
}

// Project vertex i (0-15) of 4D cell at (ox,oy,oz,ow) ± half into 3D world space.
// Scale is applied to 4D coordinates before rotation+projection, matching
// how TesseractSponge scales its vertices (pre-projection, not post-projection).
// Returns false if vertex is at or behind the eye_w clipping plane.
__device__ inline bool project_m4d_vertex(
    int i, float ox, float oy, float oz, float ow, float half,
    const Menger4DData& m, float3& out
) {
    float s = m.scale;
    float4 corner = make_float4(
        (ox + ((i & 8) ? half : -half)) * s,
        (oy + ((i & 4) ? half : -half)) * s,
        (oz + ((i & 2) ? half : -half)) * s,
        (ow + ((i & 1) ? half : -half)) * s
    );
    float4 rot = mul4x4_vec4(m.rotation4d, corner);
    if (rot.w >= m.eye_w - 1e-6f) return false;
    float3 p = persp_w(rot, m.eye_w, m.screen_w);
    out = make_float3(p.x + m.pos[0], p.y + m.pos[1], p.z + m.pos[2]);
    return true;
}

// Project all 16 corners of 4D cell to 3D AABB for conservative bounding.
__device__ inline bool project4d_box_aabb(
    float ox, float oy, float oz, float ow, float half,
    const Menger4DData& m,
    float3& box_min, float3& box_max
) {
    box_min = make_float3( 1e38f,  1e38f,  1e38f);
    box_max = make_float3(-1e38f, -1e38f, -1e38f);
    bool any_valid = false;
    for (int vi = 0; vi < 16; vi++) {
        float3 p;
        if (!project_m4d_vertex(vi, ox, oy, oz, ow, half, m, p)) continue;
        box_min = fmin3f(box_min, p);
        box_max = fmax3f(box_max, p);
        any_valid = true;
    }
    return any_valid && (box_min.x < box_max.x || box_min.y < box_max.y || box_min.z < box_max.z);
}

// Slab-based ray-AABB test.
__device__ inline bool ray_aabb_test(
    float3 ro, float3 rd,
    float3 bmin, float3 bmax,
    float ray_tmin, float ray_tmax,
    float& t_enter, float& t_exit
) {
    float3 inv = make_float3(
        fabsf(rd.x) > 1e-12f ? 1.f/rd.x : 1e12f,
        fabsf(rd.y) > 1e-12f ? 1.f/rd.y : 1e12f,
        fabsf(rd.z) > 1e-12f ? 1.f/rd.z : 1e12f
    );
    float3 t0 = make_float3((bmin.x-ro.x)*inv.x, (bmin.y-ro.y)*inv.y, (bmin.z-ro.z)*inv.z);
    float3 t1 = make_float3((bmax.x-ro.x)*inv.x, (bmax.y-ro.y)*inv.y, (bmax.z-ro.z)*inv.z);
    float3 tmin = fmin3f(t0, t1);
    float3 tmax = fmax3f(t0, t1);
    t_enter = fmaxf(fmaxf(tmin.x, tmin.y), tmin.z);
    t_exit  = fminf(fminf(tmax.x, tmax.y), tmax.z);
    return t_enter <= t_exit && t_exit > ray_tmin && t_enter < ray_tmax;
}

// Möller–Trumbore ray-triangle intersection.
// Returns t > 0 on hit, -1 on miss.
// hit_normal: geometric normal flipped to face the incoming ray.
__device__ inline float ray_triangle_mt(
    float3 ro, float3 rd,
    float3 v0, float3 v1, float3 v2,
    float3& hit_normal
) {
    float3 e1  = make_float3(v1.x-v0.x, v1.y-v0.y, v1.z-v0.z);
    float3 e2  = make_float3(v2.x-v0.x, v2.y-v0.y, v2.z-v0.z);
    float3 h   = cross3f(rd, e2);
    float  det = dot3f(e1, h);
    if (fabsf(det) < 1e-10f) return -1.f;
    float  inv_det = 1.f / det;
    float3 s = make_float3(ro.x-v0.x, ro.y-v0.y, ro.z-v0.z);
    float  u = dot3f(s, h) * inv_det;
    if (u < -1e-5f || u > 1.f + 1e-5f) return -1.f;
    float3 q = cross3f(s, e1);
    float  v = dot3f(rd, q) * inv_det;
    if (v < -1e-5f || u + v > 1.f + 1e-5f) return -1.f;
    float  t = dot3f(e2, q) * inv_det;
    if (t < 1e-6f) return -1.f;
    hit_normal = make_float3(0.f, 1.f, 0.f);  // caller overrides with Newell normal
    return t;
}

// ---- IFS traversal stack entry ----

struct M4DEntry {
    float ox, oy, oz, ow;
    float half;
    int   depth;
};

#define M4D_MAX_STACK 512

extern "C" __global__ void __intersection__menger4d() {
    const unsigned int instanceId = optixGetInstanceId();
    if (instanceId >= params.num_instances || !params.instance_materials) return;

    const InstanceMaterial& mat = params.instance_materials[instanceId];
    const int m4d_idx = mat.geometry_data_index;
    if (m4d_idx < 0 || m4d_idx >= static_cast<int>(params.num_menger4d)) return;
    if (!params.menger4d_data) return;

    const Menger4DData& m = params.menger4d_data[m4d_idx];

    const float3 ray_orig = optixGetWorldRayOrigin();
    const float3 ray_dir  = optixGetWorldRayDirection();
    const float  ray_tmin = optixGetRayTmin();
    const float  ray_tmax = optixGetRayTmax();

    M4DEntry stack[M4D_MAX_STACK];
    int top = 0;
    stack[top++] = {0.f, 0.f, 0.f, 0.f, 0.5f, 0};

    float  best_t      = ray_tmax;
    float3 best_normal = make_float3(0.f, 1.f, 0.f);

    while (top > 0) {
        M4DEntry e = stack[--top];

        float3 bmin, bmax;
        if (!project4d_box_aabb(e.ox, e.oy, e.oz, e.ow, e.half, m, bmin, bmax)) continue;
        float t_enter, t_exit;
        if (!ray_aabb_test(ray_orig, ray_dir, bmin, bmax, ray_tmin, best_t, t_enter, t_exit)) continue;

        if (e.depth == m.level) {
            // Leaf: project 16 vertices, test 24 quad faces (48 triangles).
            // Newell's method per quad matches Mesh4DProjection.faceToTriangleMesh normal.
            float3 verts[16];
            bool   valid[16];
            for (int vi = 0; vi < 16; vi++) {
                valid[vi] = project_m4d_vertex(vi, e.ox, e.oy, e.oz, e.ow, e.half, m, verts[vi]);
            }
            for (int qi = 0; qi < 24; qi++) {
                int q0 = MENGER4D_QUADS[qi][0];
                int q1 = MENGER4D_QUADS[qi][1];
                int q2 = MENGER4D_QUADS[qi][2];
                int q3 = MENGER4D_QUADS[qi][3];
                if (!valid[q0] || !valid[q1] || !valid[q2] || !valid[q3]) continue;
                // Compute Newell normal once for the entire quad face.
                // No backface culling: 4D perspective projection flips winding
                // inconsistently across quads. Match TesseractSponge (hit_triangle.cu:156)
                // which also hits both sides and flips normal to face the camera in closesthit.
                float3 qn = newell_normal_quad(verts[q0], verts[q1], verts[q2], verts[q3]);
                float3 dummy_n;
                // Fan triangulation: (q0,q1,q2) and (q0,q2,q3) — same as Scala faceToTriangleMesh
                float t1 = ray_triangle_mt(ray_orig, ray_dir, verts[q0], verts[q1], verts[q2], dummy_n);
                if (t1 > ray_tmin && t1 < best_t) { best_t = t1; best_normal = qn; }
                float t2 = ray_triangle_mt(ray_orig, ray_dir, verts[q0], verts[q2], verts[q3], dummy_n);
                if (t2 > ray_tmin && t2 < best_t) { best_t = t2; best_normal = qn; }
            }
        } else {
            // Push children, filtering by dist_threshold
            if (top + menger4d_generator_count <= M4D_MAX_STACK) {
                for (int g = 0; g < menger4d_generator_count; g++) {
                    if (menger4d_generator_abssum[g] <= m.dist_threshold) continue;
                    float gx = menger4d_generators[g][0];
                    float gy = menger4d_generators[g][1];
                    float gz = menger4d_generators[g][2];
                    float gw = menger4d_generators[g][3];
                    float ch = e.half / 3.f;
                    float d  = ch * 2.f;  // step = child_size = 2 * child_half
                    stack[top++] = {
                        e.ox + gx * d,
                        e.oy + gy * d,
                        e.oz + gz * d,
                        e.ow + gw * d,
                        ch,
                        e.depth + 1
                    };
                }
            }
        }
    }

    if (best_t < ray_tmax) {
        optixReportIntersection(
            best_t, 0,
            __float_as_uint(best_normal.x),
            __float_as_uint(best_normal.y),
            __float_as_uint(best_normal.z)
        );
    }
}

extern "C" __global__ void __closesthit__menger4d() {
    const float t = optixGetRayTmax();
    const float3 ray_origin    = optixGetWorldRayOrigin();
    const float3 ray_direction = optixGetWorldRayDirection();
    const float3 hit_point = make_float3(
        ray_origin.x + ray_direction.x * t,
        ray_origin.y + ray_direction.y * t,
        ray_origin.z + ray_direction.z * t
    );

    float3 normal = make_float3(
        __uint_as_float(optixGetAttribute_0()),
        __uint_as_float(optixGetAttribute_1()),
        __uint_as_float(optixGetAttribute_2())
    );
    // Determine entering before flipping normal.
    // 4D perspective projection can flip winding inconsistently; flip normal to face camera.
    const bool entering = (dot3f(ray_direction, normal) < 0.f);
    if (!entering)
        normal = make_float3(-normal.x, -normal.y, -normal.z);

    const unsigned int depth = optixGetPayload_3();

    if (params.stats) {
        atomicMax(&params.stats->max_depth_reached, depth + 1);
        atomicMin(&params.stats->min_depth_reached, depth + 1);
    }

    float4 material_color;
    float material_ior, roughness, metallic, specular, emission, film_thickness, cauchy_a, cauchy_b;
    getInstanceMaterialPBR(material_color, material_ior, roughness, metallic, specular, emission, film_thickness, cauchy_a, cauchy_b);

    int proc_type; float proc_scale;
    getInstanceProceduralParams(proc_type, proc_scale);
    if (proc_type != 0)
        material_color = applyProceduralTexture(material_color, hit_point, normal, proc_type, proc_scale);

    writeDenoiseGuides(material_color, normal, hit_point);

    const float alpha = material_color.w;

    if (alpha < RayTracingConstants::ALPHA_FULLY_TRANSPARENT_THRESHOLD) {
        handleFullyTransparent(hit_point, ray_direction, depth);
        return;
    }

    if (alpha >= RayTracingConstants::ALPHA_FULLY_OPAQUE_THRESHOLD) {
        if (metallic > 0.0f) {
            handleMetallicOpaque(hit_point, ray_direction, normal,
                                 material_color, metallic, depth, emission);
        } else {
            handleFullyOpaque(hit_point, normal, material_color, emission);
        }
        return;
    }

    // Semi-transparent (glass/water) path: refraction + reflection + Fresnel blend
    if (depth >= static_cast<unsigned int>(params.max_ray_depth)) {
        traceFinalNonRecursiveRay(hit_point, ray_direction, normal);
        return;
    }

    unsigned int reflect_r = 0, reflect_g = 0, reflect_b = 0;
    traceReflectedRay(hit_point, ray_direction, normal, depth, reflect_r, reflect_g, reflect_b);

    unsigned int refract_r = 0, refract_g = 0, refract_b = 0;
    const bool refraction_occurred = traceRefractedRay(
        hit_point, ray_direction, normal, entering, depth, material_ior,
        cauchy_a, cauchy_b,
        refract_r, refract_g, refract_b
    );

    if (!refraction_occurred) {
        refract_r = reflect_r;
        refract_g = reflect_g;
        refract_b = reflect_b;
    }

    float3 refract_color = payloadToFloat3(refract_r, refract_g, refract_b);
    refract_color = applyBeerLambertAbsorption(refract_color, t, entering, material_color);

    if (film_thickness > 0.0f) {
        const float cos_theta = fabsf(dot3f(ray_direction, normal));
        const float3 fresnel_rgb = computeThinFilmReflectance(cos_theta, material_ior, film_thickness);
        blendFresnelColorsRGBAndSetPayload(fresnel_rgb, reflect_r, reflect_g, reflect_b,
                                           refract_color, material_color, emission);
    } else {
        const float fresnel = computeFresnelReflectance(ray_direction, normal, entering, material_ior);
        blendFresnelColorsAndSetPayload(fresnel, reflect_r, reflect_g, reflect_b,
                                        refract_color, material_color, emission);
    }
}

extern "C" __global__ void __anyhit__menger4d_shadow() {
    if (!params.transparent_shadows_enabled) return;

    float4 material_color;
    float material_ior;
    getInstanceMaterial(material_color, material_ior);

    const float alpha = material_color.w;
    if (alpha >= 1.0f - 1e-4f) return;

    accumulateShadowAttenuation(alpha, material_color);
    optixIgnoreIntersection();
}

extern "C" __global__ void __closesthit__menger4d_shadow() {
    float4 material_color;
    float material_ior;
    getInstanceMaterial(material_color, material_ior);
    setShadowPayload(material_color.w, material_color);
}
