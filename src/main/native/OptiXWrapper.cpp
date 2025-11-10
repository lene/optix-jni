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
        float eye[3] = {0.0f, 0.0f, 3.0f};
        float u[3] = {1.0f, 0.0f, 0.0f};
        float v[3] = {0.0f, 1.0f, 0.0f};
        float w[3] = {0.0f, 0.0f, -1.0f};
        float fov = 60.0f;
        bool dirty = false;
    } camera;

    struct SphereParams {
        float center[3] = {0.0f, 0.0f, 0.0f};
        float radius = 1.5f;
        float color[4] = {1.0f, 1.0f, 1.0f, 1.0f};  // white, fully opaque
        float ior = 1.0f;  // air/vacuum (no refraction)
        float scale = 1.0f;  // 1.0 = meters
        bool dirty = false;
    } sphere;

    struct LightParams {
        float direction[3] = {0.5f, 0.5f, -0.5f};
        float intensity = 1.0f;
    } light;

    struct PlaneParams {
        int axis = 1;          // 0=X, 1=Y, 2=Z
        bool positive = true;  // true=positive normal, false=negative normal
        float value = -2.0f;   // position along axis
        bool dirty = false;
    } plane;

    unsigned int image_width = 800;
    unsigned int image_height = 600;

    // OptiX pipeline resources (created once, reused)
    OptixPipeline pipeline = nullptr;
    OptixModule module = nullptr;
    OptixProgramGroup raygen_prog_group = nullptr;
    OptixProgramGroup miss_prog_group = nullptr;
    OptixProgramGroup hitgroup_prog_group = nullptr;

    // GPU buffers (created once, reused)
    CUdeviceptr d_gas_output_buffer = 0;
    CUdeviceptr d_params = 0;
    CUdeviceptr d_image = 0;
    size_t cached_image_size = 0;
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
    float aspect_ratio = static_cast<float>(impl->image_width) / static_cast<float>(impl->image_height);
    float ulen = std::tan(fov * 0.5f * M_PI / 180.0f);
    float vlen = ulen / aspect_ratio;
    std::cout << "[OptiXWrapper] setCamera: dims=" << impl->image_width << "x" << impl->image_height
              << " aspect=" << aspect_ratio << " fov=" << fov << "° ulen=" << ulen << " vlen=" << vlen << std::endl;

    impl->camera.u[0] = u[0] * ulen;
    impl->camera.u[1] = u[1] * ulen;
    impl->camera.u[2] = u[2] * ulen;

    impl->camera.v[0] = v[0] * vlen;
    impl->camera.v[1] = v[1] * vlen;
    impl->camera.v[2] = v[2] * vlen;

    impl->camera.dirty = true;
}

void OptiXWrapper::updateImageDimensions(int width, int height) {
    std::cout << "[OptiXWrapper] updateImageDimensions: " << width << "x" << height
              << " (before: " << impl->image_width << "x" << impl->image_height << ")" << std::endl;
    impl->image_width = width;
    impl->image_height = height;
    std::cout << "[OptiXWrapper] updateImageDimensions: updated to " << impl->image_width
              << "x" << impl->image_height << std::endl;
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

// Create program groups (raygen, miss, hit group)
void OptiXWrapper::createProgramGroups(OptixModule sphere_module) {
    // Use OptiXContext to create program groups
    impl->raygen_prog_group = impl->optix_context.createRaygenProgramGroup(
        impl->module, "__raygen__rg"
    );

    impl->miss_prog_group = impl->optix_context.createMissProgramGroup(
        impl->module, "__miss__ms"
    );

    impl->hitgroup_prog_group = impl->optix_context.createHitgroupProgramGroup(
        impl->module, "__closesthit__ch",
        impl->module, "__intersection__sphere"
    );
}

// Create OptiX pipeline and configure stack sizes
void OptiXWrapper::createPipeline() {
    OptixProgramGroup program_groups[] = {
        impl->raygen_prog_group,
        impl->miss_prog_group,
        impl->hitgroup_prog_group
    };

    OptixPipelineCompileOptions pipeline_compile_options = getDefaultPipelineCompileOptions();

    OptixPipelineLinkOptions pipeline_link_options = {};
    pipeline_link_options.maxTraceDepth = MAX_TRACE_DEPTH;  // Defined in OptiXData.h

    // Use OptiXContext to create pipeline
    impl->pipeline = impl->optix_context.createPipeline(
        pipeline_compile_options,
        pipeline_link_options,
        program_groups,
        3
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

    // Miss record data (plane parameters moved to Params for performance)
    MissData ms_data;
    ms_data.r = OptiXConstants::DEFAULT_BG_R;
    ms_data.g = OptiXConstants::DEFAULT_BG_G;
    ms_data.b = OptiXConstants::DEFAULT_BG_B;

    impl->sbt.missRecordBase = impl->optix_context.createMissSBTRecord(
        impl->miss_prog_group,
        ms_data
    );
    impl->sbt.missRecordStrideInBytes = sizeof(MissSbtRecord);
    impl->sbt.missRecordCount = 1;

    // Hit group record data (material properties moved to Params for performance)
    HitGroupData hg_data;
    std::memcpy(hg_data.sphere_center, impl->sphere.center, sizeof(float) * 3);
    hg_data.sphere_radius = impl->sphere.radius;

    impl->sbt.hitgroupRecordBase = impl->optix_context.createHitgroupSBTRecord(
        impl->hitgroup_prog_group,
        hg_data
    );
    impl->sbt.hitgroupRecordStrideInBytes = sizeof(HitGroupSbtRecord);
    impl->sbt.hitgroupRecordCount = 1;
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
    if (impl->hitgroup_prog_group) {
        impl->optix_context.destroyProgramGroup(impl->hitgroup_prog_group);
        impl->hitgroup_prog_group = nullptr;
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

void OptiXWrapper::setLight(const float* direction, float intensity) {
    // Store and normalize light direction
    std::memcpy(impl->light.direction, direction, 3 * sizeof(float));
    VectorMath::normalize3f(impl->light.direction);
    impl->light.intensity = intensity;
}

void OptiXWrapper::setPlane(int axis, bool positive, float value) {
    impl->plane.axis = axis;
    impl->plane.positive = positive;
    impl->plane.value = value;
    impl->plane.dirty = true;  // Mark pipeline as needing rebuild
}

void OptiXWrapper::render(int width, int height, unsigned char* output) {
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
        std::memcpy(params.light_dir, impl->light.direction, sizeof(float) * 3);
        params.light_intensity = impl->light.intensity;
        params.plane_axis = impl->plane.axis;
        params.plane_positive = impl->plane.positive;
        params.plane_value = impl->plane.value;

        // Copy params to GPU
        CUDA_CHECK(cudaMemcpy(
            reinterpret_cast<void*>(impl->d_params),
            &params,
            sizeof(Params),
            cudaMemcpyHostToDevice
        ));

        // Launch OptiX using OptiXContext
        impl->optix_context.launch(
            impl->pipeline,
            impl->sbt,
            impl->d_params,
            width,
            height
        );

        // Copy result back to CPU
        CUDA_CHECK(cudaMemcpy(
            output,
            reinterpret_cast<void*>(impl->d_image),
            required_size,
            cudaMemcpyDeviceToHost
        ));

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

            // Clean up cached image buffer
            if (impl->d_image) {
                cudaFree(reinterpret_cast<void*>(impl->d_image));
                impl->d_image = 0;
                impl->cached_image_size = 0;
            }

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
