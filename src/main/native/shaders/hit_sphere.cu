//==============================================================================
// Sphere Closest Hit Shader
// Computes Fresnel reflection and Snell's law refraction for sphere geometry
//==============================================================================

//==============================================================================
// Closest hit shader - computes Fresnel reflection and Snell's law refraction
//==============================================================================
extern "C" __global__ void __closesthit__ch() {
    // Get hit group data
    const HitGroupData* hit_data = reinterpret_cast<HitGroupData*>(optixGetSbtDataPointer());

    // Get hit point (ray origin + t * ray direction)
    const float t = optixGetRayTmax();
    const float3 ray_origin = optixGetWorldRayOrigin();
    const float3 ray_direction = optixGetWorldRayDirection();
    const float3 hit_point = ray_origin + ray_direction * t;

    // Get hit type from custom intersection program
    const unsigned int hit_kind = optixGetHitKind();
    const bool entering = (hit_kind == 0);  // 0=entry, 1=exit

    // Get surface normal from intersection attributes
    const float3 outward_normal = make_float3(
        __uint_as_float(optixGetAttribute_0()),
        __uint_as_float(optixGetAttribute_1()),
        __uint_as_float(optixGetAttribute_2())
    );

    // Surface normal (points toward incoming ray)
    const float3 normal = entering ? outward_normal : make_float3(-outward_normal.x, -outward_normal.y, -outward_normal.z);

    // Get current depth from payload
    const unsigned int depth = optixGetPayload_3();

    // Track depth statistics
    if (params.stats) {
        atomicMax(&params.stats->max_depth_reached, depth + 1);
        atomicMin(&params.stats->min_depth_reached, depth + 1);
    }

    // Get material properties including PBR values (from IAS instance or global params)
    float4 material_color;
    float material_ior, roughness, metallic, specular;
    getInstanceMaterialPBR(material_color, material_ior, roughness, metallic, specular);
    const float sphere_alpha = material_color.w;

    // Handle fully transparent spheres
    if (sphere_alpha < ALPHA_FULLY_TRANSPARENT_THRESHOLD) {
        handleFullyTransparent(hit_point, ray_direction, depth);
        return;
    }

    // Handle metallic surfaces (opaque, reflective, colored reflections)
    // Metallic > 0.5 and opaque = metal material
    if (metallic > 0.5f && sphere_alpha >= ALPHA_FULLY_OPAQUE_THRESHOLD) {
        // If at max depth, trace final non-recursive ray
        if (depth >= MAX_TRACE_DEPTH) {
            traceFinalNonRecursiveRay(hit_point, ray_direction, normal);
            return;
        }

        // Trace reflection ray
        unsigned int reflect_r = 0, reflect_g = 0, reflect_b = 0;
        traceReflectedRay(hit_point, ray_direction, normal, depth, reflect_r, reflect_g, reflect_b);

        // Tint reflection by material color (metals have colored reflections)
        const float3 tint = make_float3(material_color.x, material_color.y, material_color.z);
        const float fr = fminf(static_cast<float>(reflect_r) * tint.x, 255.0f);
        const float fg = fminf(static_cast<float>(reflect_g) * tint.y, 255.0f);
        const float fb = fminf(static_cast<float>(reflect_b) * tint.z, 255.0f);

        optixSetPayload_0(static_cast<unsigned int>(fr));
        optixSetPayload_1(static_cast<unsigned int>(fg));
        optixSetPayload_2(static_cast<unsigned int>(fb));
        return;
    }

    // Handle fully opaque non-metallic spheres (diffuse shading)
    if (sphere_alpha >= ALPHA_FULLY_OPAQUE_THRESHOLD) {
        handleFullyOpaque(hit_point, normal, material_color);
        return;
    }

    // If max depth reached, trace final non-recursive ray
    if (depth >= MAX_TRACE_DEPTH) {
        traceFinalNonRecursiveRay(hit_point, ray_direction, normal);
        return;
    }

    // Compute Fresnel reflectance
    const float fresnel = computeFresnelReflectance(ray_direction, normal, entering, material_ior);

    // Trace reflected ray
    unsigned int reflect_r = 0, reflect_g = 0, reflect_b = 0;
    traceReflectedRay(hit_point, ray_direction, normal, depth, reflect_r, reflect_g, reflect_b);

    // Trace refracted ray
    unsigned int refract_r = 0, refract_g = 0, refract_b = 0;
    const bool refraction_occurred = traceRefractedRay(
        hit_point, ray_direction, normal, entering, depth, material_ior,
        refract_r, refract_g, refract_b
    );

    // Handle total internal reflection
    if (!refraction_occurred) {
        refract_r = reflect_r;
        refract_g = reflect_g;
        refract_b = reflect_b;
    }

    // Convert refracted color to float for absorption calculation
    float3 refract_color = payloadToFloat3(refract_r, refract_g, refract_b);

    // Apply Beer-Lambert absorption when exiting (sphere uses sphere_scale for distance)
    refract_color = applyBeerLambertAbsorption(refract_color, t, entering, material_color, params.sphere_scale);

    // Blend reflected and refracted colors using Fresnel and set output payloads
    blendFresnelColorsAndSetPayload(fresnel, reflect_r, reflect_g, reflect_b, refract_color);
}
