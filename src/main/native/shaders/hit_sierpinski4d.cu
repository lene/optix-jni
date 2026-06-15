//==============================================================================
// 4D Sierpinski Pentachoron — IFS Intersection Shader
//
// Renders the 4D Sierpinski pentachoron (5-cell) analog via iterative IFS
// traversal on the GPU.  O(1) VRAM: no geometry stored — each ray traverses
// the IFS stack per-thread.
//
// Algorithm:
//   Maintain a stack of (4D-offset, half-extent, depth) nodes.
//   At each node, project the 4D bounding "pentachoron cell" to a 3D AABB;
//   fast-reject rays that miss the AABB.  At leaf depth, project all 5
//   vertices of the 4D pentachoron cell and ray-triangle test against the
//   10 triangular faces (all C(5,3) triples).
//
// IFS generators — G[i] = 2 * v[i] where v[i] are the 5 pentachoron vertices
// from Pentachoron.scala (normalised so the root cell has half-extent 0.5):
//   R5 = sqrt(0.2)
//   G[0] = ( 2,  2,  2, -2*R5)
//   G[1] = ( 2, -2, -2, -2*R5)
//   G[2] = (-2,  2, -2, -2*R5)
//   G[3] = (-2, -2,  2, -2*R5)
//   G[4] = ( 0,  0,  0,  8*R5)
//
// Child IFS update:
//   child_half   = parent_half / 2
//   child_center = parent_center + G[i] * child_half
//   (factor 2 is already baked into G[i], so no extra multiplier needed)
//
// Vertex i of the current cell (IFS space):
//   vertex_i = center + G[i] * half
//   Apply m.scale before rotation + perspective projection.
//
// This file is included by optix_shaders.cu — do not compile separately.
//==============================================================================

// ---- 5 IFS generators (pentachoron vertices × 2) ----
// R5 = sqrt(0.2) ≈ 0.447213f
#define S4D_R5 0.447213595f

static __constant__ float sierpinski4d_generators[5][4] = {
    { 2.f,  2.f,  2.f, -2.f * S4D_R5},   // G[0]
    { 2.f, -2.f, -2.f, -2.f * S4D_R5},   // G[1]
    {-2.f,  2.f, -2.f, -2.f * S4D_R5},   // G[2]
    {-2.f, -2.f,  2.f, -2.f * S4D_R5},   // G[3]
    { 0.f,  0.f,  0.f,  8.f * S4D_R5},   // G[4]
};

static __constant__ int sierpinski4d_generator_count = 5;

// ---- 10 triangular faces — all C(5,3) triples of vertices 0-4 ----
static __device__ __constant__ int SIERPINSKI4D_TRIS[10][3] = {
    {0,1,2}, {0,1,3}, {0,1,4},
    {0,2,3}, {0,2,4}, {0,3,4},
    {1,2,3}, {1,2,4}, {1,3,4},
    {2,3,4},
};

// ---- IFS traversal stack entry ----

struct S4DEntry {
    float ox, oy, oz, ow;
    float half;
    int   depth;
};

#define S4D_MAX_STACK 128

// Project vertex i (0-4) of 4D pentachoron cell at (ox,oy,oz,ow) into 3D world space.
// Vertex i is at: center + G[i] * half  (in IFS / pre-scale space).
// Scale is applied to 4D coordinates before rotation + perspective projection.
// Returns false if vertex is at or behind the eye_w clipping plane.
__device__ inline bool project_s4d_vertex(
    int i, float ox, float oy, float oz, float ow, float half,
    const Sierpinski4DData& s, float3& out
) {
    float s4 = s.scale;
    float gx = sierpinski4d_generators[i][0];
    float gy = sierpinski4d_generators[i][1];
    float gz = sierpinski4d_generators[i][2];
    float gw = sierpinski4d_generators[i][3];
    float4 corner = make_float4(
        (ox + gx * half) * s4,
        (oy + gy * half) * s4,
        (oz + gz * half) * s4,
        (ow + gw * half) * s4
    );
    float4 rot = mul4x4_vec4(s.rotation4d, corner);
    if (rot.w >= s.eye_w - 1e-6f) return false;
    float3 p = persp_w(rot, s.eye_w, s.screen_w);
    out = make_float3(p.x + s.pos[0], p.y + s.pos[1], p.z + s.pos[2]);
    return true;
}

// Project all 5 pentachoron vertices of the cell to a 3D AABB for conservative bounding.
__device__ inline bool project_s4d_cell_aabb(
    float ox, float oy, float oz, float ow, float half,
    const Sierpinski4DData& s,
    float3& box_min, float3& box_max
) {
    box_min = make_float3( 1e38f,  1e38f,  1e38f);
    box_max = make_float3(-1e38f, -1e38f, -1e38f);
    bool any_valid = false;
    for (int vi = 0; vi < 5; vi++) {
        float3 p;
        if (!project_s4d_vertex(vi, ox, oy, oz, ow, half, s, p)) continue;
        box_min = fmin3f(box_min, p);
        box_max = fmax3f(box_max, p);
        any_valid = true;
    }
    return any_valid && (box_min.x < box_max.x || box_min.y < box_max.y || box_min.z < box_max.z);
}

extern "C" __global__ void __intersection__sierpinski4d() {
    const unsigned int instanceId = optixGetInstanceId();
    if (instanceId >= params.num_instances || !params.instance_materials) return;

    const InstanceMaterial& mat = params.instance_materials[instanceId];
    const int s4d_idx = mat.geometry_data_index;
    if (s4d_idx < 0 || s4d_idx >= static_cast<int>(params.num_sierpinski4d)) return;
    if (!params.sierpinski4d_data) return;

    const Sierpinski4DData& s = params.sierpinski4d_data[s4d_idx];

    const float3 ray_orig = optixGetWorldRayOrigin();
    const float3 ray_dir  = optixGetWorldRayDirection();
    const float  ray_tmin = optixGetRayTmin();
    const float  ray_tmax = optixGetRayTmax();

    S4DEntry stack[S4D_MAX_STACK];
    int top = 0;
    stack[top++] = {0.f, 0.f, 0.f, 0.f, 0.5f, 0};

    float  best_t      = ray_tmax;
    float3 best_normal = make_float3(0.f, 1.f, 0.f);

    while (top > 0) {
        S4DEntry e = stack[--top];

        float3 bmin, bmax;
        if (!project_s4d_cell_aabb(e.ox, e.oy, e.oz, e.ow, e.half, s, bmin, bmax)) continue;
        float t_enter, t_exit;
        if (!ray_aabb_test(ray_orig, ray_dir, bmin, bmax, ray_tmin, best_t, t_enter, t_exit)) continue;

        if (e.depth == s.level) {
            // Leaf: project 5 vertices, test 10 triangular faces.
            // Cross-product normal per triangle; no backface culling —
            // 4D perspective projection flips winding inconsistently.
            float3 verts[5];
            bool   valid[5];
            for (int vi = 0; vi < 5; vi++) {
                valid[vi] = project_s4d_vertex(vi, e.ox, e.oy, e.oz, e.ow, e.half, s, verts[vi]);
            }
            for (int fi = 0; fi < 10; fi++) {
                int i0 = SIERPINSKI4D_TRIS[fi][0];
                int i1 = SIERPINSKI4D_TRIS[fi][1];
                int i2 = SIERPINSKI4D_TRIS[fi][2];
                if (!valid[i0] || !valid[i1] || !valid[i2]) continue;

                // Geometric normal from cross product of edges.
                float3 e1n = make_float3(verts[i1].x - verts[i0].x,
                                         verts[i1].y - verts[i0].y,
                                         verts[i1].z - verts[i0].z);
                float3 e2n = make_float3(verts[i2].x - verts[i0].x,
                                         verts[i2].y - verts[i0].y,
                                         verts[i2].z - verts[i0].z);
                float3 fn = cross3f(e1n, e2n);
                float fn_len = sqrtf(dot3f(fn, fn));
                if (fn_len < 1e-10f) continue;
                fn = make_float3(fn.x / fn_len, fn.y / fn_len, fn.z / fn_len);

                float3 dummy_n;
                float t = ray_triangle_mt(ray_orig, ray_dir, verts[i0], verts[i1], verts[i2], dummy_n);
                if (t > ray_tmin && t < best_t) {
                    best_t      = t;
                    best_normal = fn;
                }
            }
        } else {
            // Push all 5 children (pentachoron: no threshold filter)
            if (top + sierpinski4d_generator_count <= S4D_MAX_STACK) {
                for (int g = 0; g < sierpinski4d_generator_count; g++) {
                    float gx = sierpinski4d_generators[g][0];
                    float gy = sierpinski4d_generators[g][1];
                    float gz = sierpinski4d_generators[g][2];
                    float gw = sierpinski4d_generators[g][3];
                    float ch = e.half / 2.f;
                    // child_center = parent_center + G[g] * child_half
                    // G[g] already encodes the factor-2, so no extra multiplier.
                    stack[top++] = {
                        e.ox + gx * ch,
                        e.oy + gy * ch,
                        e.oz + gz * ch,
                        e.ow + gw * ch,
                        ch,
                        e.depth + 1
                    };
                }
            }
        }
    }

    if (best_t < ray_tmax) {
        optixReportIntersection(
            best_t + s.hit_bias, 0,
            __float_as_uint(best_normal.x),
            __float_as_uint(best_normal.y),
            __float_as_uint(best_normal.z)
        );
    }
}

extern "C" __global__ void __closesthit__sierpinski4d() {
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
    float material_ior, roughness, metallic, specular, emission, film_thickness;
    getInstanceMaterialPBR(material_color, material_ior, roughness, metallic, specular, emission, film_thickness);

    int proc_type; float proc_scale;
    getInstanceProceduralParams(proc_type, proc_scale);
    if (proc_type != 0)
        material_color = applyProceduralTexture(material_color, hit_point, normal, proc_type, proc_scale);

    writeDenoiseGuides(material_color, normal);

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

extern "C" __global__ void __anyhit__sierpinski4d_shadow() {
    if (!params.transparent_shadows_enabled) return;

    float4 material_color;
    float material_ior;
    getInstanceMaterial(material_color, material_ior);

    const float alpha = material_color.w;
    if (alpha >= 1.0f - 1e-4f) return;

    accumulateShadowAttenuation(alpha, material_color);
    optixIgnoreIntersection();
}

extern "C" __global__ void __closesthit__sierpinski4d_shadow() {
    float4 material_color;
    float material_ior;
    getInstanceMaterial(material_color, material_ior);
    setShadowPayload(material_color.w, material_color);
}
