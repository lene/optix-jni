#include "include/OptiXWrapper.h"
#include "include/OptiXContext.h"
#include "include/OptiXConstants.h"
#include "include/OptiXErrorChecking.h"
#include "include/OptiXFileUtils.h"
#include "include/VectorMath.h"
#include <iostream>
#include <cstring>
#include <sstream>
#include <cmath>
#include <fstream>
#include <vector>
#include <cerrno>
#include <unistd.h>
#include <cuda_runtime.h>
#include <optix_function_table_definition.h>
#include <optix_stubs.h>
#include <optix_stack_size.h>

// OptiX log callback
static void optixLogCallback(unsigned int level, const char* tag, const char* message, void* /*cbdata*/) {
    std::cerr << "[OptiX][" << level << "][" << tag << "]: " << message << std::endl;
}

// Helper to create default pipeline compile options
static OptixPipelineCompileOptions getDefaultPipelineCompileOptions() {
    OptixPipelineCompileOptions options = {};
    options.usesMotionBlur = false;
    options.traversableGraphFlags = OPTIX_TRAVERSABLE_GRAPH_FLAG_ALLOW_SINGLE_GAS;
    options.numPayloadValues = 4;  // RGB color + depth (optixSetPayload_0/1/2/3)
    options.numAttributeValues = 4;  // Normal x, y, z + radius from SDK intersection
    options.exceptionFlags = OPTIX_EXCEPTION_FLAG_NONE;
    options.pipelineLaunchParamsVariableName = "params";
    options.usesPrimitiveTypeFlags = OPTIX_PRIMITIVE_TYPE_FLAGS_CUSTOM;
    return options;
}

// Implementation structure
struct OptiXWrapper::Impl {
    OptiXContext optix_context;

    struct CameraParams {
        float eye[3] = {0.0f, 0.0f, RayTracingConstants::DEFAULT_CAMERA_Z_DISTANCE};
        float u[3] = {1.0f, 0.0f, 0.0f};
        float v[3] = {0.0f, 1.0f, 0.0f};
        float w[3] = {0.0f, 0.0f, -1.0f};
        float fov = RayTracingConstants::DEFAULT_FOV_DEGREES;
        bool dirty = false;
    } camera;

    struct SphereParams {
        float center[3] = {0.0f, 0.0f, 0.0f};
        float radius = RayTracingConstants::DEFAULT_SPHERE_RADIUS;
        float color[4] = {1.0f, 1.0f, 1.0f, 1.0f};  // white, fully opaque
        float ior = MaterialConstants::IOR_VACUUM;
        float scale = 1.0f;  // 1.0 = meters
        bool dirty = false;
    } sphere;

    Light lights[RayTracingConstants::MAX_LIGHTS];
    int num_lights = 1;  // Start with one default directional light

    // Constructor to initialize default light
    Impl() {
        // Default directional light from top-right-front (normalized)
        lights[0].type = LightType::DIRECTIONAL;
        lights[0].direction[0] = 0.577350f;   // 0.5 / sqrt(0.75)
        lights[0].direction[1] = 0.577350f;   // 0.5 / sqrt(0.75)
        lights[0].direction[2] = -0.577350f;  // -0.5 / sqrt(0.75)
        lights[0].position[0] = 0.0f;
        lights[0].position[1] = 0.0f;
        lights[0].position[2] = 0.0f;
        lights[0].color[0] = 1.0f;
        lights[0].color[1] = 1.0f;
        lights[0].color[2] = 1.0f;
        lights[0].intensity = 1.0f;
    }

    struct PlaneParams {
        int axis = 1;          // 0=X, 1=Y, 2=Z
        bool positive = true;  // true=positive normal, false=negative normal
        float value = RayTracingConstants::DEFAULT_FLOOR_PLANE_Y;
        bool dirty = false;
    } plane;

    bool shadows_enabled = false;      // Enable shadow ray tracing
    bool plane_solid_color = false;    // true=solid color, false=checkerboard
    // Plane colors (RGB 0.0-1.0)
    // Default: checker mode with 120/20 for high contrast pattern
    float plane_color1[3] = {RayTracingConstants::PLANE_CHECKER_LIGHT_GRAY / 255.0f,
                             RayTracingConstants::PLANE_CHECKER_LIGHT_GRAY / 255.0f,
                             RayTracingConstants::PLANE_CHECKER_LIGHT_GRAY / 255.0f};
    float plane_color2[3] = {RayTracingConstants::PLANE_CHECKER_DARK_GRAY / 255.0f,
                             RayTracingConstants::PLANE_CHECKER_DARK_GRAY / 255.0f,
                             RayTracingConstants::PLANE_CHECKER_DARK_GRAY / 255.0f};

    // Adaptive antialiasing parameters
    bool aa_enabled = false;           // Enable adaptive antialiasing
    int aa_max_depth = 2;              // Maximum recursion depth (default: 2)
    float aa_threshold = RayTracingConstants::AA_DEFAULT_THRESHOLD;  // Color difference threshold

    int image_width = -1;
    int image_height = -1;

    // OptiX pipeline resources (created once, reused)
    OptixPipeline pipeline = nullptr;
    OptixModule module = nullptr;
    OptixProgramGroup raygen_prog_group = nullptr;
    OptixProgramGroup miss_prog_group = nullptr;
    OptixProgramGroup hitgroup_prog_group = nullptr;
    OptixProgramGroup shadow_miss_prog_group = nullptr;
    OptixProgramGroup shadow_hitgroup_prog_group = nullptr;

    // Caustics program groups (for PPM rendering)
    OptixProgramGroup caustics_hitpoints_raygen = nullptr;
    OptixProgramGroup caustics_photons_raygen = nullptr;

    // GPU buffers (created once, reused)
    CUdeviceptr d_gas_output_buffer = 0;
    CUdeviceptr d_params = 0;
    CUdeviceptr d_image = 0;
    size_t cached_image_size = 0;
    CUdeviceptr d_stats = 0;  // Ray statistics buffer

    // Caustics (Progressive Photon Mapping) GPU buffers
    CUdeviceptr d_hit_points = 0;        // Array of HitPoint structs
    CUdeviceptr d_num_hit_points = 0;    // GPU counter for atomicAdd
    CUdeviceptr d_caustics_grid = 0;     // Spatial hash grid (cell -> first hit point)
    CUdeviceptr d_caustics_grid_counts = 0;  // Hit points per cell
    CUdeviceptr d_caustics_grid_offsets = 0; // Prefix sum for sorting
    size_t cached_hit_points_size = 0;
    bool caustics_enabled = false;
    int caustics_photons_per_iter = RayTracingConstants::DEFAULT_PHOTONS_PER_ITER;
    int caustics_iterations = RayTracingConstants::DEFAULT_CAUSTICS_ITERATIONS;
    float caustics_initial_radius = RayTracingConstants::DEFAULT_INITIAL_RADIUS;
    float caustics_alpha = RayTracingConstants::DEFAULT_PPM_ALPHA;
    OptixShaderBindingTable sbt = {};
    OptixTraversableHandle gas_handle = 0;

    bool pipeline_built = false;
    bool initialized = false;
};

OptiXWrapper::OptiXWrapper() : impl(std::make_unique<Impl>()) {
}

OptiXWrapper::~OptiXWrapper() {
    dispose();
}

bool OptiXWrapper::initialize() {
    try {
        // Initialize OptiX using low-level context
        bool success = impl->optix_context.initialize();
        if (!success) {
            std::cerr << "[OptiX] OptiXContext initialization failed" << std::endl;
            return false;
        }

        impl->initialized = true;
        return true;

    } catch (const std::exception& e) {
        std::cerr << "[OptiX] Initialization failed: " << e.what() << std::endl;
        return false;
    }
}

void OptiXWrapper::setSphere(float x, float y, float z, float radius) {
    impl->sphere.center[0] = x;
    impl->sphere.center[1] = y;
    impl->sphere.center[2] = z;
    impl->sphere.radius = radius;
    impl->sphere.dirty = true;
}

void OptiXWrapper::setSphereColor(float r, float g, float b, float a) {
    impl->sphere.color[0] = r;
    impl->sphere.color[1] = g;
    impl->sphere.color[2] = b;
    impl->sphere.color[3] = a;
    impl->sphere.dirty = true;
}

void OptiXWrapper::setIOR(float ior) {
    impl->sphere.ior = ior;
    impl->sphere.dirty = true;
}

void OptiXWrapper::setScale(float scale) {
    impl->sphere.scale = scale;
    impl->sphere.dirty = true;
}

void OptiXWrapper::setCamera(const float* eye, const float* lookAt, const float* up, float fov) {
    // Store eye position and FOV
    std::memcpy(impl->camera.eye, eye, 3 * sizeof(float));
    impl->camera.fov = fov;

    // Calculate W (view direction)
    impl->camera.w[0] = lookAt[0] - eye[0];
    impl->camera.w[1] = lookAt[1] - eye[1];
    impl->camera.w[2] = lookAt[2] - eye[2];
    VectorMath::normalize3f(impl->camera.w);

    // Calculate U (right = up × W)
    float u[3];
    VectorMath::cross3f(u, up, impl->camera.w);
    VectorMath::normalize3f(u);

    // Calculate V (W × U)
    float v[3];
    VectorMath::cross3f(v, impl->camera.w, u);

    // Scale by FOV and aspect ratio
    // IMPORTANT: fov parameter is HORIZONTAL FOV in degrees
    float aspect_ratio = static_cast<float>(impl->image_width) / static_cast<float>(impl->image_height);
    float ulen = std::tan(fov * 0.5f * RayTracingConstants::DEG_TO_RAD);
    float vlen = ulen / aspect_ratio;  // Vertical derived from horizontal

    impl->camera.u[0] = u[0] * ulen;
    impl->camera.u[1] = u[1] * ulen;
    impl->camera.u[2] = u[2] * ulen;

    impl->camera.v[0] = v[0] * vlen;
    impl->camera.v[1] = v[1] * vlen;
    impl->camera.v[2] = v[2] * vlen;

    impl->camera.dirty = true;
}

void OptiXWrapper::updateImageDimensions(int width, int height) {
    impl->image_width = width;
    impl->image_height = height;
}

void OptiXWrapper::buildGeometryAccelerationStructure() {
    // Create AABB (Axis-Aligned Bounding Box) for custom sphere primitive
    OptixAabb aabb;
    aabb.minX = impl->sphere.center[0] - impl->sphere.radius;
    aabb.minY = impl->sphere.center[1] - impl->sphere.radius;
    aabb.minZ = impl->sphere.center[2] - impl->sphere.radius;
    aabb.maxX = impl->sphere.center[0] + impl->sphere.radius;
    aabb.maxY = impl->sphere.center[1] + impl->sphere.radius;
    aabb.maxZ = impl->sphere.center[2] + impl->sphere.radius;

    // Configure acceleration structure build
    OptixAccelBuildOptions accel_options = {};
    accel_options.buildFlags = OPTIX_BUILD_FLAG_ALLOW_COMPACTION;
    accel_options.operation = OPTIX_BUILD_OPERATION_BUILD;

    // Use OptiXContext to build GAS
    OptiXContext::GASBuildResult result = impl->optix_context.buildCustomPrimitiveGAS(
        aabb,
        accel_options
    );

    impl->d_gas_output_buffer = result.gas_buffer;
    impl->gas_handle = result.handle;
}

// Load and compile PTX modules, return the sphere intersection module
OptixModule OptiXWrapper::loadPTXModules() {
    // Try multiple PTX locations in priority order:
    // 1. Working directory (for packaged app after OptiXRenderer extracts from JAR)
    // 2. Build output directory (for sbt run / sbt test)
    // 3. Classes directory (for IntelliJ/IDE runs)
    std::vector<std::string> ptx_search_paths = {
        "target/native/x86_64-linux/bin/sphere_combined.ptx",  // Extracted from JAR
        "optix-jni/target/native/x86_64-linux/bin/sphere_combined.ptx",  // sbt build output
        "optix-jni/target/classes/native/x86_64-linux/sphere_combined.ptx"  // sbt-jni managed
    };
    std::string ptx_content = optix_utils::readPTXFile(ptx_search_paths);

    OptixModuleCompileOptions module_compile_options = {};
    module_compile_options.maxRegisterCount = OPTIX_COMPILE_DEFAULT_MAX_REGISTER_COUNT;
    module_compile_options.optLevel = OPTIX_COMPILE_OPTIMIZATION_DEFAULT;
    module_compile_options.debugLevel = OPTIX_COMPILE_DEBUG_LEVEL_MINIMAL;

    OptixPipelineCompileOptions pipeline_compile_options = getDefaultPipelineCompileOptions();

    // Use OptiXContext to create module
    impl->module = impl->optix_context.createModuleFromPTX(
        ptx_content,
        module_compile_options,
        pipeline_compile_options
    );

    // Custom intersection is in impl->module, no need for built-in sphere module
    return impl->module;
}

// Create program groups (raygen, miss, hit group, shadow miss, shadow hit group)
void OptiXWrapper::createProgramGroups(OptixModule sphere_module) {
    // Use OptiXContext to create program groups
    impl->raygen_prog_group = impl->optix_context.createRaygenProgramGroup(
        impl->module, "__raygen__rg"
    );

    // Primary ray miss program
    impl->miss_prog_group = impl->optix_context.createMissProgramGroup(
        impl->module, "__miss__ms"
    );

    // Shadow ray miss program
    impl->shadow_miss_prog_group = impl->optix_context.createMissProgramGroup(
        impl->module, "__miss__shadow"
    );

    // Primary ray hit group (closest hit + intersection)
    impl->hitgroup_prog_group = impl->optix_context.createHitgroupProgramGroup(
        impl->module, "__closesthit__ch",
        impl->module, "__intersection__sphere"
    );

    // Shadow ray hit group (only closest hit, same intersection program)
    impl->shadow_hitgroup_prog_group = impl->optix_context.createHitgroupProgramGroup(
        impl->module, "__closesthit__shadow",
        impl->module, "__intersection__sphere"
    );

    // Caustics raygen programs (for Progressive Photon Mapping)
    impl->caustics_hitpoints_raygen = impl->optix_context.createRaygenProgramGroup(
        impl->module, "__raygen__hitpoints"
    );
    impl->caustics_photons_raygen = impl->optix_context.createRaygenProgramGroup(
        impl->module, "__raygen__photons"
    );
}

// Create OptiX pipeline and configure stack sizes
void OptiXWrapper::createPipeline() {
    OptixProgramGroup program_groups[] = {
        impl->raygen_prog_group,
        impl->miss_prog_group,
        impl->hitgroup_prog_group,
        impl->shadow_miss_prog_group,
        impl->shadow_hitgroup_prog_group,
        impl->caustics_hitpoints_raygen,
        impl->caustics_photons_raygen
    };

    OptixPipelineCompileOptions pipeline_compile_options = getDefaultPipelineCompileOptions();

    OptixPipelineLinkOptions pipeline_link_options = {};
    pipeline_link_options.maxTraceDepth = MAX_TRACE_DEPTH;  // Defined in OptiXData.h

    // Use OptiXContext to create pipeline
    impl->pipeline = impl->optix_context.createPipeline(
        pipeline_compile_options,
        pipeline_link_options,
        program_groups,
        7  // Updated to include caustics raygen programs
    );
}

// Set up Shader Binding Table (SBT)
void OptiXWrapper::setupShaderBindingTable() {
    // Clean up old SBT records if they exist (for pipeline rebuild)
    if (impl->sbt.raygenRecord) {
        impl->optix_context.freeSBTRecord(impl->sbt.raygenRecord);
        impl->sbt.raygenRecord = 0;
    }
    if (impl->sbt.missRecordBase) {
        impl->optix_context.freeSBTRecord(impl->sbt.missRecordBase);
        impl->sbt.missRecordBase = 0;
    }
    if (impl->sbt.hitgroupRecordBase) {
        impl->optix_context.freeSBTRecord(impl->sbt.hitgroupRecordBase);
        impl->sbt.hitgroupRecordBase = 0;
    }

    // Ray generation record data
    RayGenData rg_data;
    std::memcpy(rg_data.cam_eye, impl->camera.eye, sizeof(float) * 3);
    std::memcpy(rg_data.camera_u, impl->camera.u, sizeof(float) * 3);
    std::memcpy(rg_data.camera_v, impl->camera.v, sizeof(float) * 3);
    std::memcpy(rg_data.camera_w, impl->camera.w, sizeof(float) * 3);

    impl->sbt.raygenRecord = impl->optix_context.createRaygenSBTRecord(
        impl->raygen_prog_group,
        rg_data
    );

    // Miss records: [0] = primary ray miss, [1] = shadow ray miss
    MissData ms_data;
    ms_data.r = OptiXConstants::DEFAULT_BG_R;
    ms_data.g = OptiXConstants::DEFAULT_BG_G;
    ms_data.b = OptiXConstants::DEFAULT_BG_B;

    // Allocate array for 2 miss records
    MissSbtRecord miss_records[2];
    optixSbtRecordPackHeader(impl->miss_prog_group, &miss_records[0]);
    miss_records[0].data = ms_data;

    // Shadow miss has no data (just marks as not occluded)
    optixSbtRecordPackHeader(impl->shadow_miss_prog_group, &miss_records[1]);
    miss_records[1].data = ms_data;  // Reuse same data (not used by shadow miss)

    CUdeviceptr d_miss_records;
    CUDA_CHECK(cudaMalloc(reinterpret_cast<void**>(&d_miss_records), sizeof(miss_records)));
    CUDA_CHECK(cudaMemcpy(
        reinterpret_cast<void*>(d_miss_records),
        miss_records,
        sizeof(miss_records),
        cudaMemcpyHostToDevice
    ));
    impl->sbt.missRecordBase = d_miss_records;
    impl->sbt.missRecordStrideInBytes = sizeof(MissSbtRecord);
    impl->sbt.missRecordCount = 2;

    // Hit group records: [0] = primary ray hitgroup, [1] = shadow ray hitgroup
    HitGroupData hg_data;
    std::memcpy(hg_data.sphere_center, impl->sphere.center, sizeof(float) * 3);
    hg_data.sphere_radius = impl->sphere.radius;

    // Allocate array for 2 hitgroup records
    HitGroupSbtRecord hitgroup_records[2];
    optixSbtRecordPackHeader(impl->hitgroup_prog_group, &hitgroup_records[0]);
    hitgroup_records[0].data = hg_data;

    // Shadow hitgroup uses same geometry data
    optixSbtRecordPackHeader(impl->shadow_hitgroup_prog_group, &hitgroup_records[1]);
    hitgroup_records[1].data = hg_data;

    CUdeviceptr d_hitgroup_records;
    CUDA_CHECK(cudaMalloc(reinterpret_cast<void**>(&d_hitgroup_records), sizeof(hitgroup_records)));
    CUDA_CHECK(cudaMemcpy(
        reinterpret_cast<void*>(d_hitgroup_records),
        hitgroup_records,
        sizeof(hitgroup_records),
        cudaMemcpyHostToDevice
    ));
    impl->sbt.hitgroupRecordBase = d_hitgroup_records;
    impl->sbt.hitgroupRecordStrideInBytes = sizeof(HitGroupSbtRecord);
    impl->sbt.hitgroupRecordCount = 2;
}

// Build OptiX pipeline (orchestrates all pipeline build steps)
void OptiXWrapper::buildPipeline() {
    // Clean up old pipeline resources if they exist (for pipeline rebuild)
    if (impl->pipeline) {
        impl->optix_context.destroyPipeline(impl->pipeline);
        impl->pipeline = nullptr;
    }
    if (impl->raygen_prog_group) {
        impl->optix_context.destroyProgramGroup(impl->raygen_prog_group);
        impl->raygen_prog_group = nullptr;
    }
    if (impl->miss_prog_group) {
        impl->optix_context.destroyProgramGroup(impl->miss_prog_group);
        impl->miss_prog_group = nullptr;
    }
    if (impl->shadow_miss_prog_group) {
        impl->optix_context.destroyProgramGroup(impl->shadow_miss_prog_group);
        impl->shadow_miss_prog_group = nullptr;
    }
    if (impl->hitgroup_prog_group) {
        impl->optix_context.destroyProgramGroup(impl->hitgroup_prog_group);
        impl->hitgroup_prog_group = nullptr;
    }
    if (impl->shadow_hitgroup_prog_group) {
        impl->optix_context.destroyProgramGroup(impl->shadow_hitgroup_prog_group);
        impl->shadow_hitgroup_prog_group = nullptr;
    }
    if (impl->module) {
        impl->optix_context.destroyModule(impl->module);
        impl->module = nullptr;
    }

    buildGeometryAccelerationStructure();
    OptixModule sphere_module = loadPTXModules();
    createProgramGroups(sphere_module);
    createPipeline();
    setupShaderBindingTable();

    // Allocate params buffer (only on first build)
    if (!impl->d_params) {
        CUDA_CHECK(cudaMalloc(reinterpret_cast<void**>(&impl->d_params), sizeof(Params)));
    }
}

void OptiXWrapper::setLights(const Light* lights, int count) {
    if (count < 0 || count > RayTracingConstants::MAX_LIGHTS) {
        throw std::invalid_argument(
            "Light count " + std::to_string(count) +
            " out of range [0, " + std::to_string(RayTracingConstants::MAX_LIGHTS) + "]"
        );
    }

    if (lights == nullptr && count > 0) {
        throw std::invalid_argument("Light array is null but count is " + std::to_string(count));
    }

    // Copy lights array
    impl->num_lights = count;
    for (int i = 0; i < count; ++i) {
        impl->lights[i] = lights[i];
    }
}

void OptiXWrapper::setLight(const float* direction, float intensity) {
    // Backward compatibility: convert single light to Light struct
    Light light;
    light.type = LightType::DIRECTIONAL;

    // Normalize direction
    float normalized_dir[3];
    std::memcpy(normalized_dir, direction, 3 * sizeof(float));
    VectorMath::normalize3f(normalized_dir);
    std::memcpy(light.direction, normalized_dir, 3 * sizeof(float));

    // Position unused for directional lights
    light.position[0] = 0.0f;
    light.position[1] = 0.0f;
    light.position[2] = 0.0f;

    // White light
    light.color[0] = 1.0f;
    light.color[1] = 1.0f;
    light.color[2] = 1.0f;

    light.intensity = intensity;

    // Set as single light
    setLights(&light, 1);
}

void OptiXWrapper::setShadows(bool enabled) {
    impl->shadows_enabled = enabled;
    // Synchronized to GPU params before render
}

void OptiXWrapper::setPlaneSolidColor(float r, float g, float b) {
    impl->plane_solid_color = true;
    impl->plane_color1[0] = r;
    impl->plane_color1[1] = g;
    impl->plane_color1[2] = b;
    // Synchronized to GPU params before render
}

void OptiXWrapper::setPlaneCheckerColors(float r1, float g1, float b1, float r2, float g2, float b2) {
    impl->plane_color1[0] = r1;
    impl->plane_color1[1] = g1;
    impl->plane_color1[2] = b1;
    impl->plane_color2[0] = r2;
    impl->plane_color2[1] = g2;
    impl->plane_color2[2] = b2;
    // Synchronized to GPU params before render
}

void OptiXWrapper::setAntialiasing(bool enabled, int maxDepth, float threshold) {
    impl->aa_enabled = enabled;
    impl->aa_max_depth = maxDepth;
    impl->aa_threshold = threshold;
    // Synchronized to GPU params before render
}

void OptiXWrapper::setCaustics(bool enabled, int photonsPerIter, int iterations, float initialRadius, float alpha) {
    impl->caustics_enabled = enabled;
    impl->caustics_photons_per_iter = photonsPerIter;
    impl->caustics_iterations = iterations;
    impl->caustics_initial_radius = initialRadius;
    impl->caustics_alpha = alpha;
    // GPU buffers allocated lazily when caustics rendering is requested
}

void OptiXWrapper::setPlane(int axis, bool positive, float value) {
    impl->plane.axis = axis;
    impl->plane.positive = positive;
    impl->plane.value = value;
    impl->plane.dirty = true;  // Mark pipeline as needing rebuild
}

void OptiXWrapper::render(int width, int height, unsigned char* output, RayStats* stats) {
    if (!impl->initialized) {
        throw std::runtime_error("[OptiX] render() called before initialize()");
    }

    try {
        // Note: Image dimensions for aspect ratio should be set via updateImageDimensions()
        // BEFORE calling setCamera(). Do not update them here!

        // Build OptiX pipeline on first render call or when params change
        if (!impl->pipeline_built || impl->plane.dirty || impl->sphere.dirty || impl->camera.dirty) {
            // Debug: Pipeline rebuild due to parameter change
            buildPipeline();
            impl->pipeline_built = true;
            impl->plane.dirty = false;
            impl->sphere.dirty = false;
            impl->camera.dirty = false;
        }

        // Reuse GPU image buffer (allocate only if size changed)
        const size_t required_size = width * height * 4; // RGBA
        if (impl->cached_image_size != required_size) {
            // Free old buffer if it exists
            if (impl->d_image) {
                CUDA_CHECK(cudaFree(reinterpret_cast<void*>(impl->d_image)));
                impl->d_image = 0;
                impl->cached_image_size = 0;
            }
            // Allocate new buffer with exact required size
            CUDA_CHECK(cudaMalloc(reinterpret_cast<void**>(&impl->d_image), required_size));
            impl->cached_image_size = required_size;
        }

        // Allocate stats buffer on first render
        if (!impl->d_stats) {
            CUDA_CHECK(cudaMalloc(reinterpret_cast<void**>(&impl->d_stats), sizeof(RayStats)));
        }

        // Zero out stats buffer before render
        RayStats zero_stats = {};
        zero_stats.min_depth_reached = UINT_MAX;  // Initialize to max value for atomicMin
        CUDA_CHECK(cudaMemcpy(
            reinterpret_cast<void*>(impl->d_stats),
            &zero_stats,
            sizeof(RayStats),
            cudaMemcpyHostToDevice
        ));

        // Allocate caustics buffers if enabled (lazy allocation)
        if (impl->caustics_enabled) {
            const size_t hit_points_size = RayTracingConstants::MAX_HIT_POINTS * sizeof(HitPoint);
            if (impl->cached_hit_points_size != hit_points_size) {
                // Free existing buffers
                if (impl->d_hit_points) {
                    CUDA_CHECK(cudaFree(reinterpret_cast<void*>(impl->d_hit_points)));
                }
                if (impl->d_num_hit_points) {
                    CUDA_CHECK(cudaFree(reinterpret_cast<void*>(impl->d_num_hit_points)));
                }
                if (impl->d_caustics_grid) {
                    CUDA_CHECK(cudaFree(reinterpret_cast<void*>(impl->d_caustics_grid)));
                }
                if (impl->d_caustics_grid_counts) {
                    CUDA_CHECK(cudaFree(reinterpret_cast<void*>(impl->d_caustics_grid_counts)));
                }
                if (impl->d_caustics_grid_offsets) {
                    CUDA_CHECK(cudaFree(reinterpret_cast<void*>(impl->d_caustics_grid_offsets)));
                }

                // Allocate hit points array
                CUDA_CHECK(cudaMalloc(reinterpret_cast<void**>(&impl->d_hit_points), hit_points_size));

                // Allocate counter for atomicAdd
                CUDA_CHECK(cudaMalloc(reinterpret_cast<void**>(&impl->d_num_hit_points), sizeof(unsigned int)));

                // Allocate spatial hash grid
                const size_t grid_size = RayTracingConstants::CAUSTICS_GRID_RESOLUTION *
                                         RayTracingConstants::CAUSTICS_GRID_RESOLUTION *
                                         RayTracingConstants::CAUSTICS_GRID_RESOLUTION;
                CUDA_CHECK(cudaMalloc(reinterpret_cast<void**>(&impl->d_caustics_grid), grid_size * sizeof(unsigned int)));
                CUDA_CHECK(cudaMalloc(reinterpret_cast<void**>(&impl->d_caustics_grid_counts), grid_size * sizeof(unsigned int)));
                CUDA_CHECK(cudaMalloc(reinterpret_cast<void**>(&impl->d_caustics_grid_offsets), grid_size * sizeof(unsigned int)));

                impl->cached_hit_points_size = hit_points_size;
            }

            // Zero out hit point counter
            unsigned int zero = 0;
            CUDA_CHECK(cudaMemcpy(
                reinterpret_cast<void*>(impl->d_num_hit_points),
                &zero,
                sizeof(unsigned int),
                cudaMemcpyHostToDevice
            ));
        }

        // Set up launch parameters (use cached buffer)
        Params params;
        params.image = reinterpret_cast<unsigned char*>(impl->d_image);
        params.image_width = width;
        params.image_height = height;
        params.handle = impl->gas_handle;

        // Dynamic scene data (moved from SBT for performance)
        std::memcpy(params.sphere_color, impl->sphere.color, sizeof(float) * 4);
        params.sphere_ior = impl->sphere.ior;
        params.sphere_scale = impl->sphere.scale;

        // Copy lights array
        params.num_lights = impl->num_lights;
        for (int i = 0; i < impl->num_lights; ++i) {
            params.lights[i] = impl->lights[i];
        }

        params.shadows_enabled = impl->shadows_enabled;
        params.plane_axis = impl->plane.axis;
        params.plane_positive = impl->plane.positive;
        params.plane_value = impl->plane.value;
        params.plane_solid_color = impl->plane_solid_color;
        for (int i = 0; i < 3; ++i) {
            params.plane_color1[i] = impl->plane_color1[i];
            params.plane_color2[i] = impl->plane_color2[i];
        }

        // Adaptive antialiasing parameters
        params.aa_enabled = impl->aa_enabled;
        params.aa_max_depth = impl->aa_max_depth;
        params.aa_threshold = impl->aa_threshold;

        // Caustics (Progressive Photon Mapping) parameters
        params.caustics.enabled = impl->caustics_enabled;
        params.caustics.photons_per_iteration = impl->caustics_photons_per_iter;
        params.caustics.iterations = impl->caustics_iterations;
        params.caustics.initial_radius = impl->caustics_initial_radius;
        params.caustics.alpha = impl->caustics_alpha;
        params.caustics.current_iteration = 0;
        params.caustics.hit_points = reinterpret_cast<HitPoint*>(impl->d_hit_points);
        params.caustics.num_hit_points = reinterpret_cast<unsigned int*>(impl->d_num_hit_points);
        params.caustics.grid = reinterpret_cast<unsigned int*>(impl->d_caustics_grid);
        params.caustics.grid_counts = reinterpret_cast<unsigned int*>(impl->d_caustics_grid_counts);
        params.caustics.grid_offsets = reinterpret_cast<unsigned int*>(impl->d_caustics_grid_offsets);
        params.caustics.grid_resolution = RayTracingConstants::CAUSTICS_GRID_RESOLUTION;
        params.caustics.total_photons_traced = 0;

        params.stats = reinterpret_cast<RayStats*>(impl->d_stats);

        // Copy params to GPU
        CUDA_CHECK(cudaMemcpy(
            reinterpret_cast<void*>(impl->d_params),
            &params,
            sizeof(Params),
            cudaMemcpyHostToDevice
        ));

        // Launch OptiX rendering
        if (impl->caustics_enabled) {
            // Multi-pass Progressive Photon Mapping rendering
            renderWithCaustics(width, height, params);
        } else {
            // Standard single-pass rendering
            impl->optix_context.launch(
                impl->pipeline,
                impl->sbt,
                impl->d_params,
                width,
                height
            );
        }

        // Synchronize to flush printf output
        CUDA_CHECK(cudaDeviceSynchronize());

        // Copy result back to CPU
        CUDA_CHECK(cudaMemcpy(
            output,
            reinterpret_cast<void*>(impl->d_image),
            required_size,
            cudaMemcpyDeviceToHost
        ));

        // Copy stats back if requested
        if (stats) {
            CUDA_CHECK(cudaMemcpy(
                stats,
                reinterpret_cast<void*>(impl->d_stats),
                sizeof(RayStats),
                cudaMemcpyDeviceToHost
            ));
        }

    } catch (const std::exception& e) {
        std::cerr << "[OptiX] Render failed: " << e.what() << std::endl;
        // Fill with error color (red)
        for (int i = 0; i < width * height; i++) {
            output[i * 4 + 0] = 255;  // R
            output[i * 4 + 1] = 0;    // G
            output[i * 4 + 2] = 0;    // B
            output[i * 4 + 3] = 255;  // A
        }
    }
}

void OptiXWrapper::launchCausticsPass(int width, int height, OptixProgramGroup raygen_group, int launch_width, int launch_height) {
    // Create a temporary SBT with the specified raygen program
    // This is more efficient than rebuilding the entire SBT for each pass
    OptixShaderBindingTable temp_sbt = impl->sbt;

    // Create new raygen record for this program group
    RayGenData rg_data;
    std::memcpy(rg_data.cam_eye, impl->camera.eye, sizeof(float) * 3);
    std::memcpy(rg_data.camera_u, impl->camera.u, sizeof(float) * 3);
    std::memcpy(rg_data.camera_v, impl->camera.v, sizeof(float) * 3);
    std::memcpy(rg_data.camera_w, impl->camera.w, sizeof(float) * 3);

    CUdeviceptr temp_raygen_record = impl->optix_context.createRaygenSBTRecord(
        raygen_group,
        rg_data
    );
    temp_sbt.raygenRecord = temp_raygen_record;

    // Launch with temporary SBT
    impl->optix_context.launch(
        impl->pipeline,
        temp_sbt,
        impl->d_params,
        launch_width,
        launch_height
    );

    // Clean up temporary raygen record
    impl->optix_context.freeSBTRecord(temp_raygen_record);
}

void OptiXWrapper::renderWithCaustics(int width, int height, Params& params) {
    // Progressive Photon Mapping multi-pass rendering:
    // 1. Hit Point Generation: Trace camera rays, store hit points on diffuse surfaces
    // 2. Photon Tracing Iterations: Emit photons from lights, deposit at hit points
    // 3. Final Render: Standard ray trace with caustics contribution

    // =====================================
    // Pass 1: Hit Point Generation
    // =====================================
    // Zero out hit point counter
    unsigned int zero = 0;
    CUDA_CHECK(cudaMemcpy(
        reinterpret_cast<void*>(impl->d_num_hit_points),
        &zero,
        sizeof(unsigned int),
        cudaMemcpyHostToDevice
    ));

    // Launch hit point generation (one thread per pixel)
    launchCausticsPass(width, height, impl->caustics_hitpoints_raygen, width, height);
    CUDA_CHECK(cudaDeviceSynchronize());

    // Read back number of hit points for debugging
    unsigned int num_hit_points = 0;
    CUDA_CHECK(cudaMemcpy(
        &num_hit_points,
        reinterpret_cast<void*>(impl->d_num_hit_points),
        sizeof(unsigned int),
        cudaMemcpyDeviceToHost
    ));

    // =====================================
    // Pass 2-N: Photon Tracing Iterations
    // =====================================
    for (int iter = 0; iter < impl->caustics_iterations; ++iter) {
        // Update iteration counter in params
        params.caustics.current_iteration = iter;
        CUDA_CHECK(cudaMemcpy(
            reinterpret_cast<void*>(impl->d_params),
            &params,
            sizeof(Params),
            cudaMemcpyHostToDevice
        ));

        // Calculate launch dimensions for photon tracing
        // Use a 2D grid that covers photons_per_iteration threads
        int photons = impl->caustics_photons_per_iter;
        int photon_grid_width = std::min(photons, 1024);  // Max 1024 threads per row
        int photon_grid_height = (photons + photon_grid_width - 1) / photon_grid_width;

        // Launch photon tracing
        launchCausticsPass(width, height, impl->caustics_photons_raygen, photon_grid_width, photon_grid_height);
        CUDA_CHECK(cudaDeviceSynchronize());
    }

    // =====================================
    // Final Pass: Standard Render with Caustics
    // =====================================
    // Update total photons traced
    params.caustics.total_photons_traced =
        static_cast<unsigned long long>(impl->caustics_photons_per_iter) *
        static_cast<unsigned long long>(impl->caustics_iterations);
    CUDA_CHECK(cudaMemcpy(
        reinterpret_cast<void*>(impl->d_params),
        &params,
        sizeof(Params),
        cudaMemcpyHostToDevice
    ));

    // Launch standard render (uses accumulated caustics data)
    impl->optix_context.launch(
        impl->pipeline,
        impl->sbt,
        impl->d_params,
        width,
        height
    );
}

void OptiXWrapper::dispose() {
    if (impl->initialized) {
        try {
            // Clean up OptiX pipeline resources using OptiXContext
            if (impl->pipeline) {
                impl->optix_context.destroyPipeline(impl->pipeline);
                impl->pipeline = nullptr;
            }

            if (impl->raygen_prog_group) {
                impl->optix_context.destroyProgramGroup(impl->raygen_prog_group);
                impl->raygen_prog_group = nullptr;
            }

            if (impl->miss_prog_group) {
                impl->optix_context.destroyProgramGroup(impl->miss_prog_group);
                impl->miss_prog_group = nullptr;
            }

            if (impl->hitgroup_prog_group) {
                impl->optix_context.destroyProgramGroup(impl->hitgroup_prog_group);
                impl->hitgroup_prog_group = nullptr;
            }

            if (impl->shadow_miss_prog_group) {
                impl->optix_context.destroyProgramGroup(impl->shadow_miss_prog_group);
                impl->shadow_miss_prog_group = nullptr;
            }

            if (impl->shadow_hitgroup_prog_group) {
                impl->optix_context.destroyProgramGroup(impl->shadow_hitgroup_prog_group);
                impl->shadow_hitgroup_prog_group = nullptr;
            }

            if (impl->caustics_hitpoints_raygen) {
                impl->optix_context.destroyProgramGroup(impl->caustics_hitpoints_raygen);
                impl->caustics_hitpoints_raygen = nullptr;
            }

            if (impl->caustics_photons_raygen) {
                impl->optix_context.destroyProgramGroup(impl->caustics_photons_raygen);
                impl->caustics_photons_raygen = nullptr;
            }

            if (impl->module) {
                impl->optix_context.destroyModule(impl->module);
                impl->module = nullptr;
            }

            // Clean up GPU buffers
            if (impl->d_gas_output_buffer) {
                impl->optix_context.destroyGAS(impl->d_gas_output_buffer);
                impl->d_gas_output_buffer = 0;
            }

            if (impl->d_params) {
                cudaFree(reinterpret_cast<void*>(impl->d_params));
                impl->d_params = 0;
            }

            if (impl->d_stats) {
                cudaFree(reinterpret_cast<void*>(impl->d_stats));
                impl->d_stats = 0;
            }

            // Clean up cached image buffer
            if (impl->d_image) {
                cudaFree(reinterpret_cast<void*>(impl->d_image));
                impl->d_image = 0;
                impl->cached_image_size = 0;
            }

            // Clean up caustics (PPM) buffers
            if (impl->d_hit_points) {
                cudaFree(reinterpret_cast<void*>(impl->d_hit_points));
                impl->d_hit_points = 0;
            }
            if (impl->d_num_hit_points) {
                cudaFree(reinterpret_cast<void*>(impl->d_num_hit_points));
                impl->d_num_hit_points = 0;
            }
            if (impl->d_caustics_grid) {
                cudaFree(reinterpret_cast<void*>(impl->d_caustics_grid));
                impl->d_caustics_grid = 0;
            }
            if (impl->d_caustics_grid_counts) {
                cudaFree(reinterpret_cast<void*>(impl->d_caustics_grid_counts));
                impl->d_caustics_grid_counts = 0;
            }
            if (impl->d_caustics_grid_offsets) {
                cudaFree(reinterpret_cast<void*>(impl->d_caustics_grid_offsets));
                impl->d_caustics_grid_offsets = 0;
            }
            impl->cached_hit_points_size = 0;

            // Clean up SBT buffers using OptiXContext
            if (impl->sbt.raygenRecord) {
                impl->optix_context.freeSBTRecord(impl->sbt.raygenRecord);
                impl->sbt.raygenRecord = 0;
            }

            if (impl->sbt.missRecordBase) {
                impl->optix_context.freeSBTRecord(impl->sbt.missRecordBase);
                impl->sbt.missRecordBase = 0;
            }

            if (impl->sbt.hitgroupRecordBase) {
                impl->optix_context.freeSBTRecord(impl->sbt.hitgroupRecordBase);
                impl->sbt.hitgroupRecordBase = 0;
            }

            // Clean up OptiX context
            impl->optix_context.destroy();

            impl->pipeline_built = false;

        } catch (const std::exception& e) {
            std::cerr << "[OptiX] Cleanup error: " << e.what() << std::endl;
        }
        impl->initialized = false;
    }
}
