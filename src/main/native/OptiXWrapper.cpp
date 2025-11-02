#include "include/OptiXWrapper.h"
#include <iostream>
#include <cstring>
#include <sstream>
#include <cmath>
#include <fstream>
#include <vector>
#include <cerrno>
#include <unistd.h>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

#if defined(HAVE_CUDA) && defined(HAVE_OPTIX)

// Constants for OptiX configuration
namespace OptiXConstants {
    constexpr size_t LOG_BUFFER_SIZE = 2048;
    constexpr unsigned int OPTIX_LOG_LEVEL_INFO = 3;  // Print Info, Warning, and Error messages

    // Default background color (dark purple/maroon)
    constexpr float DEFAULT_BG_R = 0.3f;
    constexpr float DEFAULT_BG_G = 0.1f;
    constexpr float DEFAULT_BG_B = 0.2f;
}

// Vector math helper functions
namespace VectorMath {
    // Normalize a 3D vector in place
    inline void normalize3f(float v[3]) {
        const float len = std::sqrt(v[0]*v[0] + v[1]*v[1] + v[2]*v[2]);
        v[0] /= len;
        v[1] /= len;
        v[2] /= len;
    }

    // Cross product: result = a × b
    inline void cross3f(float result[3], const float a[3], const float b[3]) {
        result[0] = a[1]*b[2] - a[2]*b[1];
        result[1] = a[2]*b[0] - a[0]*b[2];
        result[2] = a[0]*b[1] - a[1]*b[0];
    }
}
#include <cuda_runtime.h>
#include <optix_function_table_definition.h>
#include <optix_stubs.h>
#include <optix_stack_size.h>

// OptiX error checking macro
#define OPTIX_CHECK(call)                                                     \
    do {                                                                      \
        OptixResult res = call;                                               \
        if (res != OPTIX_SUCCESS) {                                           \
            std::ostringstream ss;                                            \
            ss << "OptiX call '" << #call << "' failed: "                     \
               << optixGetErrorName(res) << " (" << res << ")";               \
            throw std::runtime_error(ss.str());                               \
        }                                                                     \
    } while(0)

// CUDA error checking macro
#define CUDA_CHECK(call)                                                      \
    do {                                                                      \
        cudaError_t err = call;                                               \
        if (err != cudaSuccess) {                                             \
            std::ostringstream ss;                                            \
            ss << "CUDA call '" << #call << "' failed: "                      \
               << cudaGetErrorString(err) << " (" << err << ")";              \
            throw std::runtime_error(ss.str());                               \
        }                                                                     \
    } while(0)

// OptiX log callback
static void optixLogCallback(unsigned int level, const char* tag, const char* message, void* /*cbdata*/) {
    std::cerr << "[OptiX][" << level << "][" << tag << "]: " << message << std::endl;
}
#endif

// Implementation structure
struct OptiXWrapper::Impl {
#if defined(HAVE_CUDA) && defined(HAVE_OPTIX)
    OptixDeviceContext context = nullptr;

    // Camera parameters
    float camera_eye[3] = {0.0f, 0.0f, 3.0f};
    float camera_u[3] = {1.0f, 0.0f, 0.0f};
    float camera_v[3] = {0.0f, 1.0f, 0.0f};
    float camera_w[3] = {0.0f, 0.0f, -1.0f};
    float fov = 60.0f;

    // Image dimensions (for aspect ratio calculation)
    unsigned int image_width = 800;
    unsigned int image_height = 600;

    // Sphere parameters
    float sphere_center[3] = {0.0f, 0.0f, 0.0f};
    float sphere_radius = 1.5f;
    float sphere_color[3] = {1.0f, 1.0f, 1.0f};  // Default: white

    // Light parameters
    float light_direction[3] = {0.5f, 0.5f, -0.5f};
    float light_intensity = 1.0f;

    // OptiX pipeline resources (created once, reused)
    OptixPipeline pipeline = nullptr;
    OptixModule module = nullptr;
    OptixProgramGroup raygen_prog_group = nullptr;
    OptixProgramGroup miss_prog_group = nullptr;
    OptixProgramGroup hitgroup_prog_group = nullptr;

    // GPU buffers (created once, reused)
    CUdeviceptr d_gas_output_buffer = 0;     // Geometry acceleration structure
    CUdeviceptr d_params = 0;                 // Launch parameters
    CUdeviceptr d_vertex_buffer = 0;          // Sphere center position
    CUdeviceptr d_radius_buffer = 0;          // Sphere radius
    OptixShaderBindingTable sbt = {};
    OptixTraversableHandle gas_handle = 0;

    bool pipeline_built = false;
#endif
    bool initialized = false;
};

OptiXWrapper::OptiXWrapper() : impl(std::make_unique<Impl>()) {
}

OptiXWrapper::~OptiXWrapper() {
    dispose();
}

bool OptiXWrapper::initialize() {
#if defined(HAVE_CUDA) && defined(HAVE_OPTIX)
    try {
        // Initialize CUDA runtime
        CUDA_CHECK(cudaFree(0));

        // Initialize OptiX
        OPTIX_CHECK(optixInit());

        // Create OptiX device context
        CUcontext cuCtx = 0;  // 0 = use current CUDA context
        OptixDeviceContextOptions options = {};
        options.logCallbackFunction = &optixLogCallback;
        options.logCallbackLevel = OptiXConstants::OPTIX_LOG_LEVEL_INFO;

        OPTIX_CHECK(optixDeviceContextCreate(cuCtx, &options, &impl->context));

        impl->initialized = true;
        return true;

    } catch (const std::exception& e) {
        std::cerr << "[OptiX] Initialization failed: " << e.what() << std::endl;
        return false;
    }
#else
    // Stub implementation when CUDA/OptiX not available
    std::cout << "[OptiX] initialize() - stub (CUDA/OptiX not available)" << std::endl;
    impl->initialized = true;
    return true;
#endif
}

void OptiXWrapper::setSphere(float x, float y, float z, float radius) {
#if defined(HAVE_CUDA) && defined(HAVE_OPTIX)
    impl->sphere_center[0] = x;
    impl->sphere_center[1] = y;
    impl->sphere_center[2] = z;
    impl->sphere_radius = radius;
#else
    // Stub implementation - no-op
    (void)x; (void)y; (void)z; (void)radius; // Suppress unused parameter warnings
#endif
}

void OptiXWrapper::setSphereColor(float r, float g, float b, float a) {
#if defined(HAVE_CUDA) && defined(HAVE_OPTIX)
    impl->sphere_color[0] = r;
    impl->sphere_color[1] = g;
    impl->sphere_color[2] = b;
    impl->sphere_color[3] = a;
#else
    // Stub implementation - no-op
    (void)r; (void)g; (void)b; (void)a; // Suppress unused parameter warnings
#endif
}

void OptiXWrapper::setCamera(const float* eye, const float* lookAt, const float* up, float fov) {
#if defined(HAVE_CUDA) && defined(HAVE_OPTIX)
    // Store eye position and FOV
    std::memcpy(impl->camera_eye, eye, 3 * sizeof(float));
    impl->fov = fov;

    // Calculate W (view direction)
    impl->camera_w[0] = lookAt[0] - eye[0];
    impl->camera_w[1] = lookAt[1] - eye[1];
    impl->camera_w[2] = lookAt[2] - eye[2];
    VectorMath::normalize3f(impl->camera_w);

    // Calculate U (right = up × W)
    float u[3];
    VectorMath::cross3f(u, up, impl->camera_w);
    VectorMath::normalize3f(u);

    // Calculate V (W × U)
    float v[3];
    VectorMath::cross3f(v, impl->camera_w, u);

    // Scale by FOV and aspect ratio
    float aspect_ratio = static_cast<float>(impl->image_width) / static_cast<float>(impl->image_height);
    float ulen = std::tan(fov * 0.5f * M_PI / 180.0f);
    float vlen = ulen / aspect_ratio;

    impl->camera_u[0] = u[0] * ulen;
    impl->camera_u[1] = u[1] * ulen;
    impl->camera_u[2] = u[2] * ulen;

    impl->camera_v[0] = v[0] * vlen;
    impl->camera_v[1] = v[1] * vlen;
    impl->camera_v[2] = v[2] * vlen;
#else
    // Stub implementation - no-op
    (void)eye; (void)lookAt; (void)up; (void)fov; // Suppress unused parameter warnings
#endif
}

#if defined(HAVE_CUDA) && defined(HAVE_OPTIX)
// Helper function to read PTX file
static std::string readPTXFile(const std::string& filename) {
    std::ifstream file(filename, std::ios::binary | std::ios::ate);
    if (!file.is_open()) {
        // Try to provide helpful error information
        std::ostringstream ss;
        ss << "Failed to open PTX file: " << filename;
        ss << " (errno: " << errno << " - " << std::strerror(errno) << ")";
        throw std::runtime_error(ss.str());
    }

    std::streamsize size = file.tellg();
    file.seekg(0, std::ios::beg);

    std::string content(size, '\0');
    if (!file.read(&content[0], size)) {
        throw std::runtime_error("Failed to read PTX file: " + filename);
    }

    return content;
}

// Build geometry acceleration structure for the sphere
void OptiXWrapper::buildGeometryAccelerationStructure() {
    // Allocate and copy sphere center to GPU
    // IMPORTANT: Must use float3 which has proper CUDA alignment (16 bytes)
    float3 sphere_vertex = make_float3(impl->sphere_center[0], impl->sphere_center[1], impl->sphere_center[2]);
    CUDA_CHECK(cudaMalloc(reinterpret_cast<void**>(&impl->d_vertex_buffer), sizeof(float3)));
    CUDA_CHECK(cudaMemcpy(
        reinterpret_cast<void*>(impl->d_vertex_buffer),
        &sphere_vertex,
        sizeof(float3),
        cudaMemcpyHostToDevice
    ));

    // Allocate and copy sphere radius to GPU
    CUDA_CHECK(cudaMalloc(reinterpret_cast<void**>(&impl->d_radius_buffer), sizeof(float)));
    CUDA_CHECK(cudaMemcpy(
        reinterpret_cast<void*>(impl->d_radius_buffer),
        &impl->sphere_radius,
        sizeof(float),
        cudaMemcpyHostToDevice
    ));

    // Set up sphere build input
    OptixBuildInput sphere_input = {};
    sphere_input.type = OPTIX_BUILD_INPUT_TYPE_SPHERES;
    sphere_input.sphereArray.vertexBuffers = &impl->d_vertex_buffer;
    sphere_input.sphereArray.numVertices = 1;
    sphere_input.sphereArray.radiusBuffers = &impl->d_radius_buffer;

    uint32_t sphere_input_flags[1] = {OPTIX_GEOMETRY_FLAG_NONE};
    sphere_input.sphereArray.flags = sphere_input_flags;
    sphere_input.sphereArray.numSbtRecords = 1;
    sphere_input.sphereArray.sbtIndexOffsetBuffer = 0;
    sphere_input.sphereArray.sbtIndexOffsetSizeInBytes = 0;
    sphere_input.sphereArray.sbtIndexOffsetStrideInBytes = 0;

    // Configure acceleration structure build
    OptixAccelBuildOptions accel_options = {};
    accel_options.buildFlags = OPTIX_BUILD_FLAG_ALLOW_COMPACTION;
    accel_options.operation = OPTIX_BUILD_OPERATION_BUILD;

    // Query memory requirements
    OptixAccelBufferSizes gas_buffer_sizes;
    OPTIX_CHECK(optixAccelComputeMemoryUsage(
        impl->context,
        &accel_options,
        &sphere_input,
        1,
        &gas_buffer_sizes
    ));

    // Allocate temporary buffer
    CUdeviceptr d_temp_buffer;
    CUDA_CHECK(cudaMalloc(
        reinterpret_cast<void**>(&d_temp_buffer),
        gas_buffer_sizes.tempSizeInBytes
    ));

    // Allocate output buffer
    CUDA_CHECK(cudaMalloc(
        reinterpret_cast<void**>(&impl->d_gas_output_buffer),
        gas_buffer_sizes.outputSizeInBytes
    ));

    // Build acceleration structure
    OPTIX_CHECK(optixAccelBuild(
        impl->context,
        0, // CUDA stream
        &accel_options,
        &sphere_input,
        1,
        d_temp_buffer,
        gas_buffer_sizes.tempSizeInBytes,
        impl->d_gas_output_buffer,
        gas_buffer_sizes.outputSizeInBytes,
        &impl->gas_handle,
        nullptr, // emitted property list
        0        // num emitted properties
    ));

    // Clean up temporary buffer (keep vertex/radius buffers for rendering)
    CUDA_CHECK(cudaFree(reinterpret_cast<void*>(d_temp_buffer)));
}

// Load and compile PTX modules, return the sphere intersection module
OptixModule OptiXWrapper::loadPTXModules() {
    // Load combined PTX module (all three shaders in one file)
    // PTX file is in target/native/x86_64-linux/bin/ directory
    std::string ptx_path = "target/native/x86_64-linux/bin/sphere_combined.ptx";
    std::string ptx_content = readPTXFile(ptx_path);

    OptixModuleCompileOptions module_compile_options = {};
    module_compile_options.maxRegisterCount = OPTIX_COMPILE_DEFAULT_MAX_REGISTER_COUNT;
    module_compile_options.optLevel = OPTIX_COMPILE_OPTIMIZATION_DEFAULT;
    module_compile_options.debugLevel = OPTIX_COMPILE_DEBUG_LEVEL_MINIMAL;

    OptixPipelineCompileOptions pipeline_compile_options = {};
    pipeline_compile_options.usesMotionBlur = false;
    pipeline_compile_options.traversableGraphFlags = OPTIX_TRAVERSABLE_GRAPH_FLAG_ALLOW_SINGLE_GAS;
    pipeline_compile_options.numPayloadValues = 3; // RGB
    pipeline_compile_options.numAttributeValues = 0;
    pipeline_compile_options.exceptionFlags = OPTIX_EXCEPTION_FLAG_NONE;
    pipeline_compile_options.pipelineLaunchParamsVariableName = "params";
    pipeline_compile_options.usesPrimitiveTypeFlags = OPTIX_PRIMITIVE_TYPE_FLAGS_SPHERE;

    char log[OptiXConstants::LOG_BUFFER_SIZE];
    size_t log_size = sizeof(log);

    OPTIX_CHECK(optixModuleCreate(
        impl->context,
        &module_compile_options,
        &pipeline_compile_options,
        ptx_content.c_str(),
        ptx_content.size(),
        log,
        &log_size,
        &impl->module
    ));

    // Get built-in sphere intersection module
    OptixBuiltinISOptions builtin_is_options = {};
    builtin_is_options.usesMotionBlur = false;
    builtin_is_options.builtinISModuleType = OPTIX_PRIMITIVE_TYPE_SPHERE;

    OptixModule sphere_module = nullptr;
    OPTIX_CHECK(optixBuiltinISModuleGet(
        impl->context,
        &module_compile_options,
        &pipeline_compile_options,
        &builtin_is_options,
        &sphere_module
    ));

    return sphere_module;
}

// Create program groups (raygen, miss, hit group)
void OptiXWrapper::createProgramGroups(OptixModule sphere_module) {
    OptixProgramGroupOptions program_group_options = {};
    char log[OptiXConstants::LOG_BUFFER_SIZE];
    size_t log_size;

    // Ray generation program group
    {
        OptixProgramGroupDesc raygen_desc = {};
        raygen_desc.kind = OPTIX_PROGRAM_GROUP_KIND_RAYGEN;
        raygen_desc.raygen.module = impl->module;
        raygen_desc.raygen.entryFunctionName = "__raygen__rg";

        log_size = sizeof(log);
        OPTIX_CHECK(optixProgramGroupCreate(
            impl->context,
            &raygen_desc,
            1,
            &program_group_options,
            log,
            &log_size,
            &impl->raygen_prog_group
        ));
    }

    // Miss program group
    {
        OptixProgramGroupDesc miss_desc = {};
        miss_desc.kind = OPTIX_PROGRAM_GROUP_KIND_MISS;
        miss_desc.miss.module = impl->module;
        miss_desc.miss.entryFunctionName = "__miss__ms";

        log_size = sizeof(log);
        OPTIX_CHECK(optixProgramGroupCreate(
            impl->context,
            &miss_desc,
            1,
            &program_group_options,
            log,
            &log_size,
            &impl->miss_prog_group
        ));
    }

    // Hit group program group
    {
        OptixProgramGroupDesc hitgroup_desc = {};
        hitgroup_desc.kind = OPTIX_PROGRAM_GROUP_KIND_HITGROUP;
        hitgroup_desc.hitgroup.moduleCH = impl->module;
        hitgroup_desc.hitgroup.entryFunctionNameCH = "__closesthit__ch";
        hitgroup_desc.hitgroup.moduleAH = nullptr;
        hitgroup_desc.hitgroup.entryFunctionNameAH = nullptr;
        hitgroup_desc.hitgroup.moduleIS = sphere_module;
        hitgroup_desc.hitgroup.entryFunctionNameIS = nullptr; // Use built-in

        log_size = sizeof(log);
        OPTIX_CHECK(optixProgramGroupCreate(
            impl->context,
            &hitgroup_desc,
            1,
            &program_group_options,
            log,
            &log_size,
            &impl->hitgroup_prog_group
        ));
    }

}

// Create OptiX pipeline and configure stack sizes
void OptiXWrapper::createPipeline() {
    OptixProgramGroup program_groups[] = {
        impl->raygen_prog_group,
        impl->miss_prog_group,
        impl->hitgroup_prog_group
    };

    OptixPipelineCompileOptions pipeline_compile_options = {};
    pipeline_compile_options.usesMotionBlur = false;
    pipeline_compile_options.traversableGraphFlags = OPTIX_TRAVERSABLE_GRAPH_FLAG_ALLOW_SINGLE_GAS;
    pipeline_compile_options.numPayloadValues = 3; // RGB
    pipeline_compile_options.numAttributeValues = 0;
    pipeline_compile_options.exceptionFlags = OPTIX_EXCEPTION_FLAG_NONE;
    pipeline_compile_options.pipelineLaunchParamsVariableName = "params";
    pipeline_compile_options.usesPrimitiveTypeFlags = OPTIX_PRIMITIVE_TYPE_FLAGS_SPHERE;

    OptixPipelineLinkOptions pipeline_link_options = {};
    pipeline_link_options.maxTraceDepth = 1;

    char log[OptiXConstants::LOG_BUFFER_SIZE];
    size_t log_size = sizeof(log);
    OPTIX_CHECK(optixPipelineCreate(
        impl->context,
        &pipeline_compile_options,
        &pipeline_link_options,
        program_groups,
        3,
        log,
        &log_size,
        &impl->pipeline
    ));

    // Set stack sizes
    OptixStackSizes stack_sizes = {};
    for (auto& prog_group : program_groups) {
        OPTIX_CHECK(optixUtilAccumulateStackSizes(prog_group, &stack_sizes, impl->pipeline));
    }

    uint32_t direct_callable_stack_size_from_traversal;
    uint32_t direct_callable_stack_size_from_state;
    uint32_t continuation_stack_size;
    OPTIX_CHECK(optixUtilComputeStackSizes(
        &stack_sizes,
        1, // maxTraceDepth
        0, // maxCCDepth
        0, // maxDCDepth
        &direct_callable_stack_size_from_traversal,
        &direct_callable_stack_size_from_state,
        &continuation_stack_size
    ));

    OPTIX_CHECK(optixPipelineSetStackSize(
        impl->pipeline,
        direct_callable_stack_size_from_traversal,
        direct_callable_stack_size_from_state,
        continuation_stack_size,
        1 // maxTraversableDepth
    ));
}

// Set up Shader Binding Table (SBT)
void OptiXWrapper::setupShaderBindingTable() {
    // Ray generation record
    {
        RayGenSbtRecord rg_sbt;
        // Copy camera data
        std::memcpy(rg_sbt.data.cam_eye, impl->camera_eye, sizeof(float) * 3);
        std::memcpy(rg_sbt.data.camera_u, impl->camera_u, sizeof(float) * 3);
        std::memcpy(rg_sbt.data.camera_v, impl->camera_v, sizeof(float) * 3);
        std::memcpy(rg_sbt.data.camera_w, impl->camera_w, sizeof(float) * 3);

        OPTIX_CHECK(optixSbtRecordPackHeader(impl->raygen_prog_group, &rg_sbt));

        CUdeviceptr d_raygen_record;
        CUDA_CHECK(cudaMalloc(reinterpret_cast<void**>(&d_raygen_record), sizeof(RayGenSbtRecord)));
        CUDA_CHECK(cudaMemcpy(
            reinterpret_cast<void*>(d_raygen_record),
            &rg_sbt,
            sizeof(RayGenSbtRecord),
            cudaMemcpyHostToDevice
        ));

        impl->sbt.raygenRecord = d_raygen_record;
    }

    // Miss record
    {
        MissSbtRecord ms_sbt;
        ms_sbt.data.r = OptiXConstants::DEFAULT_BG_R;
        ms_sbt.data.g = OptiXConstants::DEFAULT_BG_G;
        ms_sbt.data.b = OptiXConstants::DEFAULT_BG_B;

        OPTIX_CHECK(optixSbtRecordPackHeader(impl->miss_prog_group, &ms_sbt));

        CUdeviceptr d_miss_record;
        CUDA_CHECK(cudaMalloc(reinterpret_cast<void**>(&d_miss_record), sizeof(MissSbtRecord)));
        CUDA_CHECK(cudaMemcpy(
            reinterpret_cast<void*>(d_miss_record),
            &ms_sbt,
            sizeof(MissSbtRecord),
            cudaMemcpyHostToDevice
        ));

        impl->sbt.missRecordBase = d_miss_record;
        impl->sbt.missRecordStrideInBytes = sizeof(MissSbtRecord);
        impl->sbt.missRecordCount = 1;
    }

    // Hit group record
    {
        HitGroupSbtRecord hg_sbt;
        std::memcpy(hg_sbt.data.sphere_center, impl->sphere_center, sizeof(float) * 3);
        std::memcpy(hg_sbt.data.sphere_color, impl->sphere_color, sizeof(float) * 3);
        std::memcpy(hg_sbt.data.light_dir, impl->light_direction, sizeof(float) * 3);
        hg_sbt.data.light_intensity = impl->light_intensity;

        OPTIX_CHECK(optixSbtRecordPackHeader(impl->hitgroup_prog_group, &hg_sbt));

        CUdeviceptr d_hitgroup_record;
        CUDA_CHECK(cudaMalloc(reinterpret_cast<void**>(&d_hitgroup_record), sizeof(HitGroupSbtRecord)));
        CUDA_CHECK(cudaMemcpy(
            reinterpret_cast<void*>(d_hitgroup_record),
            &hg_sbt,
            sizeof(HitGroupSbtRecord),
            cudaMemcpyHostToDevice
        ));

        impl->sbt.hitgroupRecordBase = d_hitgroup_record;
        impl->sbt.hitgroupRecordStrideInBytes = sizeof(HitGroupSbtRecord);
        impl->sbt.hitgroupRecordCount = 1;
    }
}

// Build OptiX pipeline (orchestrates all pipeline build steps)
void OptiXWrapper::buildPipeline() {
    buildGeometryAccelerationStructure();
    OptixModule sphere_module = loadPTXModules();
    createProgramGroups(sphere_module);
    createPipeline();
    setupShaderBindingTable();

    // Allocate params buffer
    CUDA_CHECK(cudaMalloc(reinterpret_cast<void**>(&impl->d_params), sizeof(Params)));
}
#endif

void OptiXWrapper::setLight(const float* direction, float intensity) {
#if defined(HAVE_CUDA) && defined(HAVE_OPTIX)
    // Store and normalize light direction
    std::memcpy(impl->light_direction, direction, 3 * sizeof(float));
    VectorMath::normalize3f(impl->light_direction);
    impl->light_intensity = intensity;
#else
    // Stub implementation - no-op
    (void)direction; (void)intensity; // Suppress unused parameter warnings
#endif
}

void OptiXWrapper::render(int width, int height, unsigned char* output) {
#if defined(HAVE_CUDA) && defined(HAVE_OPTIX)
    if (!impl->initialized) {
        throw std::runtime_error("[OptiX] render() called before initialize()");
    }

    try {
        // Update image dimensions for aspect ratio calculations
        impl->image_width = width;
        impl->image_height = height;

        // Build OptiX pipeline on first render call
        if (!impl->pipeline_built) {
            buildPipeline();
            impl->pipeline_built = true;
        }

        // Allocate GPU image buffer
        CUdeviceptr d_image;
        const size_t image_size = width * height * 4; // RGBA
        CUDA_CHECK(cudaMalloc(reinterpret_cast<void**>(&d_image), image_size));

        // Set up launch parameters
        Params params;
        params.image = reinterpret_cast<unsigned char*>(d_image);
        params.image_width = width;
        params.image_height = height;
        params.handle = impl->gas_handle;

        // Copy params to GPU
        CUDA_CHECK(cudaMemcpy(
            reinterpret_cast<void*>(impl->d_params),
            &params,
            sizeof(Params),
            cudaMemcpyHostToDevice
        ));

        // Launch OptiX
        OPTIX_CHECK(optixLaunch(
            impl->pipeline,
            0, // CUDA stream
            impl->d_params,
            sizeof(Params),
            &impl->sbt,
            width,
            height,
            1 // depth
        ));

        // Wait for GPU to finish
        CUDA_CHECK(cudaDeviceSynchronize());

        // Copy result back to CPU
        CUDA_CHECK(cudaMemcpy(
            output,
            reinterpret_cast<void*>(d_image),
            image_size,
            cudaMemcpyDeviceToHost
        ));

        // Clean up GPU image buffer
        CUDA_CHECK(cudaFree(reinterpret_cast<void*>(d_image)));

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
#else
    // Stub implementation - return gray placeholder
    std::memset(output, 128, width * height * 4 - 1); // Fill RGB with 128
    for (int i = 3; i < width * height * 4; i += 4) {
        output[i] = 255; // Alpha channel
    }
#endif
}

void OptiXWrapper::dispose() {
    if (impl->initialized) {
#if defined(HAVE_CUDA) && defined(HAVE_OPTIX)
        try {
            // Clean up OptiX pipeline resources
            if (impl->pipeline) {
                optixPipelineDestroy(impl->pipeline);
                impl->pipeline = nullptr;
            }

            if (impl->raygen_prog_group) {
                optixProgramGroupDestroy(impl->raygen_prog_group);
                impl->raygen_prog_group = nullptr;
            }

            if (impl->miss_prog_group) {
                optixProgramGroupDestroy(impl->miss_prog_group);
                impl->miss_prog_group = nullptr;
            }

            if (impl->hitgroup_prog_group) {
                optixProgramGroupDestroy(impl->hitgroup_prog_group);
                impl->hitgroup_prog_group = nullptr;
            }

            if (impl->module) {
                optixModuleDestroy(impl->module);
                impl->module = nullptr;
            }

            // Clean up GPU buffers
            if (impl->d_gas_output_buffer) {
                cudaFree(reinterpret_cast<void*>(impl->d_gas_output_buffer));
                impl->d_gas_output_buffer = 0;
            }

            if (impl->d_params) {
                cudaFree(reinterpret_cast<void*>(impl->d_params));
                impl->d_params = 0;
            }

            if (impl->d_vertex_buffer) {
                cudaFree(reinterpret_cast<void*>(impl->d_vertex_buffer));
                impl->d_vertex_buffer = 0;
            }

            if (impl->d_radius_buffer) {
                cudaFree(reinterpret_cast<void*>(impl->d_radius_buffer));
                impl->d_radius_buffer = 0;
            }

            // Clean up SBT buffers
            if (impl->sbt.raygenRecord) {
                cudaFree(reinterpret_cast<void*>(impl->sbt.raygenRecord));
                impl->sbt.raygenRecord = 0;
            }

            if (impl->sbt.missRecordBase) {
                cudaFree(reinterpret_cast<void*>(impl->sbt.missRecordBase));
                impl->sbt.missRecordBase = 0;
            }

            if (impl->sbt.hitgroupRecordBase) {
                cudaFree(reinterpret_cast<void*>(impl->sbt.hitgroupRecordBase));
                impl->sbt.hitgroupRecordBase = 0;
            }

            // Clean up OptiX context
            if (impl->context) {
                optixDeviceContextDestroy(impl->context);
                impl->context = nullptr;
            }

            impl->pipeline_built = false;

        } catch (const std::exception& e) {
            std::cerr << "[OptiX] Cleanup error: " << e.what() << std::endl;
        }
#endif
        impl->initialized = false;
    }
}
