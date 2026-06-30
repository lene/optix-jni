//==============================================================================
// Curve Closest Hit Shader
// Handles built-in round cubic B-spline curve geometry
//==============================================================================
// This file is included by optix_shaders.cu - do not compile separately

__device__ float3 cubicBSplineCenter(const float4 control_points[4], float u) {
    const float u2 = u * u;
    const float u3 = u2 * u;

    const float b0 = (1.0f - 3.0f * u + 3.0f * u2 - u3) / 6.0f;
    const float b1 = (4.0f - 6.0f * u2 + 3.0f * u3) / 6.0f;
    const float b2 = (1.0f + 3.0f * u + 3.0f * u2 - 3.0f * u3) / 6.0f;
    const float b3 = u3 / 6.0f;

    return make_float3(
        b0 * control_points[0].x + b1 * control_points[1].x
            + b2 * control_points[2].x + b3 * control_points[3].x,
        b0 * control_points[0].y + b1 * control_points[1].y
            + b2 * control_points[2].y + b3 * control_points[3].y,
        b0 * control_points[0].z + b1 * control_points[1].z
            + b2 * control_points[2].z + b3 * control_points[3].z
    );
}

__device__ float3 computeCurveNormalWorld() {
    float4 control_points[4];
    optixGetCubicBSplineVertexData(control_points);

    const float t = optixGetRayTmax();
    const float3 ray_origin = optixGetWorldRayOrigin();
    const float3 ray_direction = optixGetWorldRayDirection();
    const float3 hit_point_world = ray_origin + ray_direction * t;
    const float3 hit_point_object = optixTransformPointFromWorldToObjectSpace(hit_point_world);

    const float3 center_object = cubicBSplineCenter(control_points, optixGetCurveParameter());
    float3 normal_object = hit_point_object - center_object;
    if (length(normal_object) < 1.0e-8f) {
        normal_object = make_float3(0.0f, 1.0f, 0.0f);
    } else {
        normal_object = normalize(normal_object);
    }

    return normalize(optixTransformNormalFromObjectToWorldSpace(normal_object));
}

extern "C" __global__ void __closesthit__curve() {
    const float t = optixGetRayTmax();
    const float3 ray_origin = optixGetWorldRayOrigin();
    const float3 ray_direction = optixGetWorldRayDirection();
    const float3 hit_point = ray_origin + ray_direction * t;

    const float3 outward_normal = computeCurveNormalWorld();
    const bool entering = dot(ray_direction, outward_normal) < 0.0f;
    float3 normal = entering
        ? outward_normal
        : make_float3(-outward_normal.x, -outward_normal.y, -outward_normal.z);

    const unsigned int depth = optixGetPayload_3();

    if (params.stats) {
        atomicMax(&params.stats->max_depth_reached, depth + 1);
        atomicMin(&params.stats->min_depth_reached, depth + 1);
    }

    float4 material_color;
    float material_ior, roughness, metallic, specular, emission, film_thickness, cauchy_a, cauchy_b;
    getInstanceMaterialPBR(
        material_color, material_ior, roughness, metallic, specular, emission, film_thickness, cauchy_a, cauchy_b
    );

    int proc_type;
    float proc_scale;
    getInstanceProceduralParams(proc_type, proc_scale);
    if (proc_type != 0) {
        material_color = applyProceduralTexture(
            material_color, hit_point, normal, proc_type, proc_scale
        );
    }

    writeDenoiseGuides(material_color, normal);

    const float alpha = material_color.w;

    if (alpha < ALPHA_FULLY_TRANSPARENT_THRESHOLD) {
        handleFullyTransparent(hit_point, ray_direction, depth);
        return;
    }

    if (alpha >= ALPHA_FULLY_OPAQUE_THRESHOLD) {
        if (metallic > 0.0f) {
            handleMetallicOpaque(
                hit_point, ray_direction, normal, material_color, metallic, depth, emission
            );
            return;
        }
        handleFullyOpaque(hit_point, normal, material_color, emission);
        return;
    }

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
        const float cos_theta = fabsf(dot(ray_direction, normal));
        const float3 fresnel_rgb = computeThinFilmReflectance(
            cos_theta, material_ior, film_thickness
        );
        blendFresnelColorsRGBAndSetPayload(
            fresnel_rgb, reflect_r, reflect_g, reflect_b, refract_color, material_color, emission
        );
    } else {
        const float fresnel = computeFresnelReflectance(
            ray_direction, normal, entering, material_ior
        );
        blendFresnelColorsAndSetPayload(
            fresnel, reflect_r, reflect_g, reflect_b, refract_color, material_color, emission
        );
    }
}

extern "C" __global__ void __anyhit__curve_shadow() {
    if (!params.transparent_shadows_enabled) return;

    float4 material_color;
    float material_ior;
    getInstanceMaterial(material_color, material_ior);

    const float alpha = material_color.w;
    if (alpha >= 1.0f - 1e-4f) return;

    accumulateShadowAttenuation(alpha, material_color);
    optixIgnoreIntersection();
}

extern "C" __global__ void __closesthit__curve_shadow() {
    float4 material_color;
    float material_ior;
    getInstanceMaterial(material_color, material_ior);
    setShadowPayload(material_color.w, material_color);
}
