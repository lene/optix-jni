#include "include/OptiXContext.h"
#include "include/OptiXConstants.h"
#include "include/OptiXData.h"
#include "include/OptiXErrorChecking.h"

#include <iostream>
#include <sstream>
#include <fstream>
#include <memory>
#include <cerrno>
#include <cstring>
#include <algorithm>
#include <atomic>
#include <filesystem>
#include <unistd.h>
#include <pwd.h>

#include <cuda.h>
#include <optix_stubs.h>
#include <optix_stack_size.h>

// Track cache corruption - when detected, clear and rebuild cache
static std::atomic<bool> g_cache_corruption_detected{false};

std::string OptiXContext::getDefaultCachePath() {
    const char* user = std::getenv("USER");
    if (user == nullptr) user = std::getenv("LOGNAME");

    // Fallback to getpwuid for Docker/CI environments where env vars aren't set
    if (user == nullptr) {
        const struct passwd* pw = getpwuid(getuid());
        if (pw != nullptr) {
            user = pw->pw_name;
        }
    }

    // Last resort: use uid as identifier
    if (user == nullptr) {
        return std::string("/var/tmp/OptixCache_") + std::to_string(getuid());
    }

    return std::string("/var/tmp/OptixCache_") + user;
}

bool OptiXContext::clearCache(const std::string& cache_path) {
    if (cache_path.empty()) return true;

    try {
        if (std::filesystem::exists(cache_path)) {
            std::filesystem::remove_all(cache_path);
            std::cerr << "[OptiXContext] Cleared cache: " << cache_path << std::endl;
        }
        return true;
    } catch (const std::filesystem::filesystem_error& e) {
        std::cerr << "[OptiXContext] Failed to clear cache: " << e.what() << std::endl;
        return false;
    }
}

bool OptiXContext::clearCache() {
    return clearCache(getDefaultCachePath());
}

// OptiX log callback - detects cache corruption for auto-recovery
static void optixLogCallback(unsigned int level, const char* tag, const char* message, void* /*cbdata*/) {
    std::cerr << "[OptiX][" << level << "]["
              << (tag     ? tag     : "") << "]: "
              << (message ? message : "") << std::endl;

    // Detect cache corruption: tag is "DISKCACHE" and message contains corruption indicators
    // Common error messages:
    //   "disk I/O error"
    //   "database disk image is malformed"
    //   "corruption detected"
    //   "Error when configuring the database"
    if (tag != nullptr && std::strcmp(tag, "DISKCACHE") == 0 && message != nullptr) {
        if (std::strstr(message, "corruption") != nullptr ||
            std::strstr(message, "malformed") != nullptr ||
            std::strstr(message, "I/O error") != nullptr ||
            std::strstr(message, "Error when configuring") != nullptr) {
            if (!g_cache_corruption_detected.exchange(true)) {
                // First detection - clear the cache
                OptiXContext::clearCache();
            }
        }
    }
}

OptiXContext::OptiXContext() : context_(nullptr), initialized_(false) {
}

OptiXContext::~OptiXContext() {
    destroy();
}

bool OptiXContext::initialize() {
    try {
        // Step 1: Initialize CUDA Driver API explicitly.
        // libnvoptix.so is a driver component and uses driver API internally.
        // On systems with a CUDA 12.x-era libnvoptix.so running inside a CUDA 13.x
        // container, optixDeviceContextCreate() can hang if the CUDA runtime creates
        // the context first (runtime context initialization differs between versions).
        // Initializing via the driver API first avoids this incompatibility.
        CUresult cu_result = cuInit(0);
        if (cu_result != CUDA_SUCCESS) {
            const char* err_str = nullptr;
            cuGetErrorString(cu_result, &err_str);
            std::cerr << "[OptiXContext] cuInit failed: "
                      << (err_str ? err_str : "unknown") << std::endl;
            return false;
        }

        CUdevice cu_device = 0;
        cu_result = cuDeviceGet(&cu_device, 0);
        if (cu_result != CUDA_SUCCESS) {
            std::cerr << "[OptiXContext] cuDeviceGet failed" << std::endl;
            return false;
        }

        // Retain the primary context via driver API. This is the same context
        // that the CUDA runtime uses, ensuring both APIs share one context.
        CUcontext cu_ctx = nullptr;
        cu_result = cuDevicePrimaryCtxRetain(&cu_ctx, cu_device);
        if (cu_result != CUDA_SUCCESS || cu_ctx == nullptr) {
            std::cerr << "[OptiXContext] cuDevicePrimaryCtxRetain failed" << std::endl;
            return false;
        }

        cu_result = cuCtxSetCurrent(cu_ctx);
        if (cu_result != CUDA_SUCCESS) {
            std::cerr << "[OptiXContext] cuCtxSetCurrent failed" << std::endl;
            return false;
        }

        // Step 2: Initialize CUDA runtime (shares the primary context above)
        CUDA_CHECK(cudaFree(0));

        // Step 3: Initialize OptiX
        OPTIX_CHECK(optixInit());

        // Step 4: Create OptiX device context with the explicit driver-API context
        OptixDeviceContextOptions options = {};
        options.logCallbackFunction = &optixLogCallback;
        options.logCallbackLevel = OptiXConstants::OPTIX_LOG_LEVEL_INFO;

        OPTIX_CHECK(optixDeviceContextCreate(cu_ctx, &options, &context_));

        // Enable validation mode when MENGER_OPTIX_VALIDATION=1
        // Catches SBT mismatches, payload size errors, and buffer alignment issues
        // at the precise call site instead of getting CUDA error 718 at launch.
        const char* validation_env = std::getenv("MENGER_OPTIX_VALIDATION");
        if (validation_env != nullptr && std::string(validation_env) == "1") {
            OPTIX_CHECK(optixDeviceContextSetValidationMode(
                context_, OPTIX_DEVICE_CONTEXT_VALIDATION_MODE_ALL));
            std::cout << "[OptiX] Validation mode enabled" << std::endl;
        }

        // Configure OptiX disk cache
        // Allow custom cache location via MENGER_OPTIX_CACHE environment variable
        const char* cache_path = std::getenv("MENGER_OPTIX_CACHE");
        if (cache_path != nullptr) {
            OPTIX_CHECK(optixDeviceContextSetCacheLocation(context_, cache_path));
        }

        // Reset corruption flag - cache was cleared, fresh one will be created
        g_cache_corruption_detected.store(false);

        initialized_ = true;
        return true;

    } catch (const std::exception& e) {
        std::cerr << "[OptiXContext] Initialization failed: " << e.what() << std::endl;
        return false;
    }
}

void OptiXContext::destroy() {
    if (initialized_ && context_) {
        try {
            optixDeviceContextDestroy(context_);
            context_ = nullptr;
            initialized_ = false;
        } catch (const std::exception& e) {
            std::cerr << "[OptiXContext] Cleanup error: " << e.what() << std::endl;
        }
    }
}

OptixModule OptiXContext::createModuleFromPTX(
    const std::string& ptx_content,
    const OptixModuleCompileOptions& module_options,
    const OptixPipelineCompileOptions& pipeline_options)
{
    if (!initialized_ || !context_)
        throw std::runtime_error("OptiXContext: not initialized");

    char log[OptiXConstants::LOG_BUFFER_SIZE];
    size_t log_size = sizeof(log);

    OptixModule module = nullptr;
    OPTIX_CHECK(optixModuleCreate(
        context_,
        &module_options,
        &pipeline_options,
        ptx_content.c_str(),
        ptx_content.size(),
        log,
        &log_size,
        &module
    ));

    return module;
}

void OptiXContext::destroyModule(OptixModule module) {
    if (module) {
        optixModuleDestroy(module);
    }
}

OptixProgramGroup OptiXContext::createRaygenProgramGroup(
    OptixModule module,
    const char* entry_function_name)
{
    OptixProgramGroupOptions program_group_options = {};
    OptixProgramGroupDesc raygen_desc = {};
    raygen_desc.kind = OPTIX_PROGRAM_GROUP_KIND_RAYGEN;
    raygen_desc.raygen.module = module;
    raygen_desc.raygen.entryFunctionName = entry_function_name;

    char log[OptiXConstants::LOG_BUFFER_SIZE];
    size_t log_size = sizeof(log);

    OptixProgramGroup program_group = nullptr;
    OPTIX_CHECK(optixProgramGroupCreate(
        context_,
        &raygen_desc,
        1,
        &program_group_options,
        log,
        &log_size,
        &program_group
    ));

    return program_group;
}

OptixProgramGroup OptiXContext::createMissProgramGroup(
    OptixModule module,
    const char* entry_function_name)
{
    OptixProgramGroupOptions program_group_options = {};
    OptixProgramGroupDesc miss_desc = {};
    miss_desc.kind = OPTIX_PROGRAM_GROUP_KIND_MISS;
    miss_desc.miss.module = module;
    miss_desc.miss.entryFunctionName = entry_function_name;

    char log[OptiXConstants::LOG_BUFFER_SIZE];
    size_t log_size = sizeof(log);

    OptixProgramGroup program_group = nullptr;
    OPTIX_CHECK(optixProgramGroupCreate(
        context_,
        &miss_desc,
        1,
        &program_group_options,
        log,
        &log_size,
        &program_group
    ));

    return program_group;
}

OptixProgramGroup OptiXContext::createHitgroupProgramGroup(
    OptixModule module_ch,
    const char* entry_ch,
    OptixModule module_is,
    const char* entry_is)
{
    OptixProgramGroupOptions program_group_options = {};
    OptixProgramGroupDesc hitgroup_desc = {};
    hitgroup_desc.kind = OPTIX_PROGRAM_GROUP_KIND_HITGROUP;
    hitgroup_desc.hitgroup.moduleCH = module_ch;
    hitgroup_desc.hitgroup.entryFunctionNameCH = entry_ch;
    hitgroup_desc.hitgroup.moduleAH = nullptr;
    hitgroup_desc.hitgroup.entryFunctionNameAH = nullptr;
    hitgroup_desc.hitgroup.moduleIS = module_is;
    hitgroup_desc.hitgroup.entryFunctionNameIS = entry_is;

    char log[OptiXConstants::LOG_BUFFER_SIZE];
    size_t log_size = sizeof(log);

    OptixProgramGroup program_group = nullptr;
    OPTIX_CHECK(optixProgramGroupCreate(
        context_,
        &hitgroup_desc,
        1,
        &program_group_options,
        log,
        &log_size,
        &program_group
    ));

    return program_group;
}

OptixProgramGroup OptiXContext::createTriangleHitgroupProgramGroup(
    OptixModule module_ch,
    const char* entry_ch)
{
    OptixProgramGroupOptions program_group_options = {};
    OptixProgramGroupDesc hitgroup_desc = {};
    hitgroup_desc.kind = OPTIX_PROGRAM_GROUP_KIND_HITGROUP;
    hitgroup_desc.hitgroup.moduleCH = module_ch;
    hitgroup_desc.hitgroup.entryFunctionNameCH = entry_ch;
    hitgroup_desc.hitgroup.moduleAH = nullptr;
    hitgroup_desc.hitgroup.entryFunctionNameAH = nullptr;
    // No intersection shader - OptiX uses built-in triangle intersection
    hitgroup_desc.hitgroup.moduleIS = nullptr;
    hitgroup_desc.hitgroup.entryFunctionNameIS = nullptr;

    char log[OptiXConstants::LOG_BUFFER_SIZE];
    size_t log_size = sizeof(log);

    OptixProgramGroup program_group = nullptr;
    OPTIX_CHECK(optixProgramGroupCreate(
        context_,
        &hitgroup_desc,
        1,
        &program_group_options,
        log,
        &log_size,
        &program_group
    ));

    return program_group;
}

OptixProgramGroup OptiXContext::createCurveHitgroupProgramGroup(
    OptixModule module_ch,
    const char* entry_ch,
    OptixModule& builtin_curve_module)
{
    OptixModuleCompileOptions module_compile_options = {};
    module_compile_options.maxRegisterCount = OPTIX_COMPILE_DEFAULT_MAX_REGISTER_COUNT;
    module_compile_options.optLevel = OPTIX_COMPILE_OPTIMIZATION_DEFAULT;
    module_compile_options.debugLevel = OPTIX_COMPILE_DEBUG_LEVEL_MINIMAL;

    OptixPipelineCompileOptions pipeline_compile_options = {};
    pipeline_compile_options.usesMotionBlur = false;
    pipeline_compile_options.traversableGraphFlags = OPTIX_TRAVERSABLE_GRAPH_FLAG_ALLOW_ANY;
    pipeline_compile_options.numPayloadValues = 10;
    pipeline_compile_options.numAttributeValues = 4;
    pipeline_compile_options.exceptionFlags = OPTIX_EXCEPTION_FLAG_NONE;
    pipeline_compile_options.pipelineLaunchParamsVariableName = "params";
    pipeline_compile_options.usesPrimitiveTypeFlags =
        OPTIX_PRIMITIVE_TYPE_FLAGS_CUSTOM
        | OPTIX_PRIMITIVE_TYPE_FLAGS_TRIANGLE
        | OPTIX_PRIMITIVE_TYPE_FLAGS_ROUND_CUBIC_BSPLINE;

    OptixBuiltinISOptions builtin_is_options = {};
    builtin_is_options.builtinISModuleType = OPTIX_PRIMITIVE_TYPE_ROUND_CUBIC_BSPLINE;
    builtin_is_options.usesMotionBlur = false;
    builtin_is_options.buildFlags = OPTIX_BUILD_FLAG_ALLOW_COMPACTION;

    OPTIX_CHECK(optixBuiltinISModuleGet(
        context_,
        &module_compile_options,
        &pipeline_compile_options,
        &builtin_is_options,
        &builtin_curve_module
    ));

    OptixProgramGroupOptions program_group_options = {};
    OptixProgramGroupDesc hitgroup_desc = {};
    hitgroup_desc.kind = OPTIX_PROGRAM_GROUP_KIND_HITGROUP;
    hitgroup_desc.hitgroup.moduleCH = module_ch;
    hitgroup_desc.hitgroup.entryFunctionNameCH = entry_ch;
    hitgroup_desc.hitgroup.moduleAH = nullptr;
    hitgroup_desc.hitgroup.entryFunctionNameAH = nullptr;
    hitgroup_desc.hitgroup.moduleIS = builtin_curve_module;
    hitgroup_desc.hitgroup.entryFunctionNameIS = nullptr;

    char log[OptiXConstants::LOG_BUFFER_SIZE];
    size_t log_size = sizeof(log);

    OptixProgramGroup program_group = nullptr;
    OPTIX_CHECK(optixProgramGroupCreate(
        context_,
        &hitgroup_desc,
        1,
        &program_group_options,
        log,
        &log_size,
        &program_group
    ));

    return program_group;
}

OptixProgramGroup OptiXContext::createHitgroupProgramGroupWithAH(
    OptixModule module_ch, const char* entry_ch,
    OptixModule module_ah, const char* entry_ah,
    OptixModule module_is, const char* entry_is)
{
    OptixProgramGroupOptions program_group_options = {};
    OptixProgramGroupDesc hitgroup_desc = {};
    hitgroup_desc.kind = OPTIX_PROGRAM_GROUP_KIND_HITGROUP;
    hitgroup_desc.hitgroup.moduleCH = module_ch;
    hitgroup_desc.hitgroup.entryFunctionNameCH = entry_ch;
    hitgroup_desc.hitgroup.moduleAH = module_ah;
    hitgroup_desc.hitgroup.entryFunctionNameAH = entry_ah;
    hitgroup_desc.hitgroup.moduleIS = module_is;
    hitgroup_desc.hitgroup.entryFunctionNameIS = entry_is;

    char log[OptiXConstants::LOG_BUFFER_SIZE];
    size_t log_size = sizeof(log);

    OptixProgramGroup program_group = nullptr;
    OPTIX_CHECK(optixProgramGroupCreate(
        context_,
        &hitgroup_desc,
        1,
        &program_group_options,
        log,
        &log_size,
        &program_group
    ));

    return program_group;
}

OptixProgramGroup OptiXContext::createTriangleHitgroupProgramGroupWithAH(
    OptixModule module_ch, const char* entry_ch,
    OptixModule module_ah, const char* entry_ah)
{
    OptixProgramGroupOptions program_group_options = {};
    OptixProgramGroupDesc hitgroup_desc = {};
    hitgroup_desc.kind = OPTIX_PROGRAM_GROUP_KIND_HITGROUP;
    hitgroup_desc.hitgroup.moduleCH = module_ch;
    hitgroup_desc.hitgroup.entryFunctionNameCH = entry_ch;
    hitgroup_desc.hitgroup.moduleAH = module_ah;
    hitgroup_desc.hitgroup.entryFunctionNameAH = entry_ah;
    // No intersection shader - OptiX uses built-in triangle intersection
    hitgroup_desc.hitgroup.moduleIS = nullptr;
    hitgroup_desc.hitgroup.entryFunctionNameIS = nullptr;

    char log[OptiXConstants::LOG_BUFFER_SIZE];
    size_t log_size = sizeof(log);

    OptixProgramGroup program_group = nullptr;
    OPTIX_CHECK(optixProgramGroupCreate(
        context_,
        &hitgroup_desc,
        1,
        &program_group_options,
        log,
        &log_size,
        &program_group
    ));

    return program_group;
}

OptixProgramGroup OptiXContext::createCurveHitgroupProgramGroupWithAH(
    OptixModule module_ch, const char* entry_ch,
    OptixModule module_ah, const char* entry_ah,
    OptixModule& builtin_curve_module)
{
    OptixModuleCompileOptions module_compile_options = {};
    module_compile_options.maxRegisterCount = OPTIX_COMPILE_DEFAULT_MAX_REGISTER_COUNT;
    module_compile_options.optLevel = OPTIX_COMPILE_OPTIMIZATION_DEFAULT;
    module_compile_options.debugLevel = OPTIX_COMPILE_DEBUG_LEVEL_MINIMAL;

    OptixPipelineCompileOptions pipeline_compile_options = {};
    pipeline_compile_options.usesMotionBlur = false;
    pipeline_compile_options.traversableGraphFlags = OPTIX_TRAVERSABLE_GRAPH_FLAG_ALLOW_ANY;
    pipeline_compile_options.numPayloadValues = 10;
    pipeline_compile_options.numAttributeValues = 4;
    pipeline_compile_options.exceptionFlags = OPTIX_EXCEPTION_FLAG_NONE;
    pipeline_compile_options.pipelineLaunchParamsVariableName = "params";
    pipeline_compile_options.usesPrimitiveTypeFlags =
        OPTIX_PRIMITIVE_TYPE_FLAGS_CUSTOM
        | OPTIX_PRIMITIVE_TYPE_FLAGS_TRIANGLE
        | OPTIX_PRIMITIVE_TYPE_FLAGS_ROUND_CUBIC_BSPLINE;

    OptixBuiltinISOptions builtin_is_options = {};
    builtin_is_options.builtinISModuleType = OPTIX_PRIMITIVE_TYPE_ROUND_CUBIC_BSPLINE;
    builtin_is_options.usesMotionBlur = false;
    builtin_is_options.buildFlags = OPTIX_BUILD_FLAG_ALLOW_COMPACTION;

    OPTIX_CHECK(optixBuiltinISModuleGet(
        context_,
        &module_compile_options,
        &pipeline_compile_options,
        &builtin_is_options,
        &builtin_curve_module
    ));

    OptixProgramGroupOptions program_group_options = {};
    OptixProgramGroupDesc hitgroup_desc = {};
    hitgroup_desc.kind = OPTIX_PROGRAM_GROUP_KIND_HITGROUP;
    hitgroup_desc.hitgroup.moduleCH = module_ch;
    hitgroup_desc.hitgroup.entryFunctionNameCH = entry_ch;
    hitgroup_desc.hitgroup.moduleAH = module_ah;
    hitgroup_desc.hitgroup.entryFunctionNameAH = entry_ah;
    hitgroup_desc.hitgroup.moduleIS = builtin_curve_module;
    hitgroup_desc.hitgroup.entryFunctionNameIS = nullptr;

    char log[OptiXConstants::LOG_BUFFER_SIZE];
    size_t log_size = sizeof(log);

    OptixProgramGroup program_group = nullptr;
    OPTIX_CHECK(optixProgramGroupCreate(
        context_,
        &hitgroup_desc,
        1,
        &program_group_options,
        log,
        &log_size,
        &program_group
    ));

    return program_group;
}

void OptiXContext::destroyProgramGroup(OptixProgramGroup program_group) {
    if (program_group) {
        optixProgramGroupDestroy(program_group);
    }
}

OptixPipeline OptiXContext::createPipeline(
    const OptixPipelineCompileOptions& pipeline_options,
    const OptixPipelineLinkOptions& link_options,
    OptixProgramGroup* program_groups,
    unsigned int num_program_groups)
{
    char log[OptiXConstants::LOG_BUFFER_SIZE];
    size_t log_size = sizeof(log);

    OptixPipeline pipeline = nullptr;
    OPTIX_CHECK(optixPipelineCreate(
        context_,
        &pipeline_options,
        &link_options,
        program_groups,
        num_program_groups,
        log,
        &log_size,
        &pipeline
    ));

    // Configure stack sizes
    OptixStackSizes stack_sizes = {};
    for (unsigned int i = 0; i < num_program_groups; ++i) {
        OPTIX_CHECK(optixUtilAccumulateStackSizes(program_groups[i], &stack_sizes, nullptr));
    }

    uint32_t max_trace_depth = MAX_TRACE_DEPTH;  // Must match shader MAX_TRACE_DEPTH
    uint32_t max_cc_depth = 0;
    uint32_t max_dc_depth = 0;
    uint32_t direct_callable_stack_size_from_traversal;
    uint32_t direct_callable_stack_size_from_state;
    uint32_t continuation_stack_size;

    OPTIX_CHECK(optixUtilComputeStackSizes(
        &stack_sizes,
        max_trace_depth,
        max_cc_depth,
        max_dc_depth,
        &direct_callable_stack_size_from_traversal,
        &direct_callable_stack_size_from_state,
        &continuation_stack_size
    ));

    // Increase continuation stack size for triangle and cylinder shaders with reflection/refraction
    // The triangle and cylinder closesthit shaders have many local variables and recursive optixTrace calls
    // that require more stack space than the utility function estimates
    // Cylinder shaders with single-bounce metallic reflection need additional stack space
    // for the handleMetallicOpaque() helper and one level of optixTrace recursion
    constexpr unsigned int MIN_CONTINUATION_STACK_SIZE = 49152u;  // 48 KB minimum for metallic cylinders
    continuation_stack_size = std::max(continuation_stack_size, MIN_CONTINUATION_STACK_SIZE);

    // maxTraversableGraphDepth: depth of the deepest traversal chain.
    //   2 covers main-IAS -> GAS (the original mixed-geometry path).
    //   Recursive-IAS Menger sponges (Sprint 18.4) chain main-IAS ->
    //   N nested sub-IASes -> cube GAS, requiring depth >= N + 2.
    //   Setting to 16 supports up to level-14 recursive sponges; higher
    //   values cost a small amount of pipeline stack but no measurable
    //   runtime overhead until the depth is actually used.
    constexpr unsigned int MAX_TRAVERSABLE_GRAPH_DEPTH = 16u;
    OPTIX_CHECK(optixPipelineSetStackSize(
        pipeline,
        direct_callable_stack_size_from_traversal,
        direct_callable_stack_size_from_state,
        continuation_stack_size,
        MAX_TRAVERSABLE_GRAPH_DEPTH
    ));

    return pipeline;
}

void OptiXContext::destroyPipeline(OptixPipeline pipeline) {
    if (pipeline) {
        optixPipelineDestroy(pipeline);
    }
}

OptiXContext::GASBuildResult OptiXContext::buildCustomPrimitiveGAS(
    const OptixAabb& aabb,
    const OptixAccelBuildOptions& build_options)
{
    // Allocate and copy AABB to GPU
    CUdeviceptr d_aabb;
    CUDA_CHECK(cudaMalloc(reinterpret_cast<void**>(&d_aabb), sizeof(OptixAabb)));
    // Guard d_aabb so it is freed if any subsequent step throws; ownership is
    // transferred to the caller via .release() once the build succeeds.
    auto d_aabb_guard = std::unique_ptr<void, decltype(&cudaFree)>(
        reinterpret_cast<void*>(d_aabb), cudaFree);
    CUDA_CHECK(cudaMemcpy(
        reinterpret_cast<void*>(d_aabb),
        &aabb,
        sizeof(OptixAabb),
        cudaMemcpyHostToDevice
    ));

    // Set up custom primitive build input
    OptixBuildInput sphere_input = {};
    sphere_input.type = OPTIX_BUILD_INPUT_TYPE_CUSTOM_PRIMITIVES;

    uint32_t sphere_input_flags[1] = {OPTIX_GEOMETRY_FLAG_NONE};

    sphere_input.customPrimitiveArray.aabbBuffers = &d_aabb;
    sphere_input.customPrimitiveArray.numPrimitives = 1;
    sphere_input.customPrimitiveArray.flags = sphere_input_flags;
    sphere_input.customPrimitiveArray.numSbtRecords = 1;
    sphere_input.customPrimitiveArray.sbtIndexOffsetBuffer = 0;
    sphere_input.customPrimitiveArray.sbtIndexOffsetSizeInBytes = 0;
    sphere_input.customPrimitiveArray.sbtIndexOffsetStrideInBytes = 0;

    // Query memory requirements
    OptixAccelBufferSizes gas_buffer_sizes;
    OPTIX_CHECK(optixAccelComputeMemoryUsage(
        context_,
        &build_options,
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
    auto d_temp_buffer_guard = std::unique_ptr<void, decltype(&cudaFree)>(
        reinterpret_cast<void*>(d_temp_buffer), cudaFree);

    // Allocate output buffer
    CUdeviceptr d_gas_output_buffer;
    CUDA_CHECK(cudaMalloc(
        reinterpret_cast<void**>(&d_gas_output_buffer),
        gas_buffer_sizes.outputSizeInBytes
    ));
    auto d_gas_output_buffer_guard = std::unique_ptr<void, decltype(&cudaFree)>(
        reinterpret_cast<void*>(d_gas_output_buffer), cudaFree);

    // Build acceleration structure
    OptixTraversableHandle gas_handle;
    OPTIX_CHECK(optixAccelBuild(
        context_,
        0, // CUDA stream
        &build_options,
        &sphere_input,
        1,
        d_temp_buffer,
        gas_buffer_sizes.tempSizeInBytes,
        d_gas_output_buffer,
        gas_buffer_sizes.outputSizeInBytes,
        &gas_handle,
        nullptr, // emitted property list
        0        // num emitted properties
    ));

    // d_temp_buffer_guard frees d_temp_buffer at end of scope
    // Transfer ownership of d_gas_output_buffer and d_aabb to caller
    d_gas_output_buffer_guard.release();
    d_aabb_guard.release();

    GASBuildResult result;
    result.gas_buffer = d_gas_output_buffer;
    result.handle = gas_handle;
    result.aabb_buffer = d_aabb;  // Keep AABB alive - caller must free it
    return result;
}

OptiXContext::GASBuildResult OptiXContext::buildTriangleGAS(
    CUdeviceptr d_vertices,
    unsigned int num_vertices,
    CUdeviceptr d_indices,
    unsigned int num_triangles,
    const OptixAccelBuildOptions& build_options,
    unsigned int vertex_stride)
{
    // Set up triangle build input
    OptixBuildInput triangle_input = {};
    triangle_input.type = OPTIX_BUILD_INPUT_TYPE_TRIANGLES;

    // Vertex buffer: positions only (OptiX needs just xyz for GAS)
    // Vertex format is [px, py, pz, nx, ny, nz, (u, v)] = vertex_stride floats
    // OptiX will read xyz at offset 0, then skip to next vertex at stride * sizeof(float)
    triangle_input.triangleArray.vertexBuffers = &d_vertices;
    triangle_input.triangleArray.numVertices = num_vertices;
    triangle_input.triangleArray.vertexFormat = OPTIX_VERTEX_FORMAT_FLOAT3;
    triangle_input.triangleArray.vertexStrideInBytes = vertex_stride * sizeof(float);

    // Index buffer: 3 unsigned ints per triangle
    triangle_input.triangleArray.indexBuffer = d_indices;
    triangle_input.triangleArray.numIndexTriplets = num_triangles;
    triangle_input.triangleArray.indexFormat = OPTIX_INDICES_FORMAT_UNSIGNED_INT3;
    triangle_input.triangleArray.indexStrideInBytes = 3 * sizeof(unsigned int);

    // Geometry flags
    uint32_t triangle_flags[1] = {OPTIX_GEOMETRY_FLAG_NONE};
    triangle_input.triangleArray.flags = triangle_flags;
    triangle_input.triangleArray.numSbtRecords = 1;
    triangle_input.triangleArray.sbtIndexOffsetBuffer = 0;
    triangle_input.triangleArray.sbtIndexOffsetSizeInBytes = 0;
    triangle_input.triangleArray.sbtIndexOffsetStrideInBytes = 0;

    // Query memory requirements
    OptixAccelBufferSizes gas_buffer_sizes;
    OPTIX_CHECK(optixAccelComputeMemoryUsage(
        context_,
        &build_options,
        &triangle_input,
        1,
        &gas_buffer_sizes
    ));

    // Allocate temporary buffer
    CUdeviceptr d_temp_buffer;
    CUDA_CHECK(cudaMalloc(
        reinterpret_cast<void**>(&d_temp_buffer),
        gas_buffer_sizes.tempSizeInBytes
    ));
    auto d_temp_buffer_guard = std::unique_ptr<void, decltype(&cudaFree)>(
        reinterpret_cast<void*>(d_temp_buffer), cudaFree);

    // Allocate output buffer
    CUdeviceptr d_gas_output_buffer;
    CUDA_CHECK(cudaMalloc(
        reinterpret_cast<void**>(&d_gas_output_buffer),
        gas_buffer_sizes.outputSizeInBytes
    ));
    auto d_gas_output_buffer_guard = std::unique_ptr<void, decltype(&cudaFree)>(
        reinterpret_cast<void*>(d_gas_output_buffer), cudaFree);

    // Build acceleration structure
    OptixTraversableHandle gas_handle;
    OPTIX_CHECK(optixAccelBuild(
        context_,
        0, // CUDA stream
        &build_options,
        &triangle_input,
        1,
        d_temp_buffer,
        gas_buffer_sizes.tempSizeInBytes,
        d_gas_output_buffer,
        gas_buffer_sizes.outputSizeInBytes,
        &gas_handle,
        nullptr, // emitted property list
        0        // num emitted properties
    ));

    // d_temp_buffer_guard frees d_temp_buffer at end of scope
    // Transfer ownership of d_gas_output_buffer to caller
    d_gas_output_buffer_guard.release();

    GASBuildResult result;
    result.gas_buffer = d_gas_output_buffer;
    result.handle = gas_handle;
    result.aabb_buffer = 0;  // Triangle meshes don't use custom AABBs
    return result;
}

OptiXContext::GASBuildResult OptiXContext::buildCurveGAS(
    CUdeviceptr d_points,
    unsigned int num_points,
    CUdeviceptr d_widths,
    CUdeviceptr d_segment_indices,
    unsigned int num_segments,
    const OptixAccelBuildOptions& build_options)
{
    OptixBuildInput curve_input = {};
    curve_input.type = OPTIX_BUILD_INPUT_TYPE_CURVES;
    curve_input.curveArray.curveType = OPTIX_PRIMITIVE_TYPE_ROUND_CUBIC_BSPLINE;
    curve_input.curveArray.numPrimitives = num_segments;
    curve_input.curveArray.vertexBuffers = &d_points;
    curve_input.curveArray.numVertices = num_points;
    curve_input.curveArray.vertexStrideInBytes = sizeof(float3);
    curve_input.curveArray.widthBuffers = &d_widths;
    curve_input.curveArray.widthStrideInBytes = sizeof(float);
    curve_input.curveArray.normalBuffers = nullptr;
    curve_input.curveArray.normalStrideInBytes = 0;
    curve_input.curveArray.indexBuffer = d_segment_indices;
    curve_input.curveArray.indexStrideInBytes = sizeof(unsigned int);
    curve_input.curveArray.flag = OPTIX_GEOMETRY_FLAG_NONE;
    curve_input.curveArray.primitiveIndexOffset = 0;
    curve_input.curveArray.endcapFlags = OPTIX_CURVE_ENDCAP_DEFAULT;

    OptixAccelBufferSizes gas_buffer_sizes;
    OPTIX_CHECK(optixAccelComputeMemoryUsage(
        context_,
        &build_options,
        &curve_input,
        1,
        &gas_buffer_sizes
    ));

    CUdeviceptr d_temp_buffer;
    CUDA_CHECK(cudaMalloc(
        reinterpret_cast<void**>(&d_temp_buffer),
        gas_buffer_sizes.tempSizeInBytes
    ));
    auto d_temp_buffer_guard = std::unique_ptr<void, decltype(&cudaFree)>(
        reinterpret_cast<void*>(d_temp_buffer), cudaFree);

    CUdeviceptr d_gas_output_buffer;
    CUDA_CHECK(cudaMalloc(
        reinterpret_cast<void**>(&d_gas_output_buffer),
        gas_buffer_sizes.outputSizeInBytes
    ));
    auto d_gas_output_buffer_guard = std::unique_ptr<void, decltype(&cudaFree)>(
        reinterpret_cast<void*>(d_gas_output_buffer), cudaFree);

    OptixTraversableHandle gas_handle;
    OPTIX_CHECK(optixAccelBuild(
        context_,
        0,
        &build_options,
        &curve_input,
        1,
        d_temp_buffer,
        gas_buffer_sizes.tempSizeInBytes,
        d_gas_output_buffer,
        gas_buffer_sizes.outputSizeInBytes,
        &gas_handle,
        nullptr,
        0
    ));

    d_gas_output_buffer_guard.release();

    GASBuildResult result;
    result.gas_buffer = d_gas_output_buffer;
    result.handle = gas_handle;
    result.aabb_buffer = 0;
    return result;
}

void OptiXContext::destroyGAS(CUdeviceptr gas_buffer) {
    if (gas_buffer) {
        cudaFree(reinterpret_cast<void*>(gas_buffer));
    }
}

// Helper template for SBT record creation
template<typename T>
static CUdeviceptr createSBTRecordHelper(OptixProgramGroup program_group, const T& data) {
    using SbtRecordType = SbtRecord<T>;
    SbtRecordType sbt_record;
    sbt_record.data = data;

    OPTIX_CHECK(optixSbtRecordPackHeader(program_group, &sbt_record));

    CUdeviceptr d_record;
    CUDA_CHECK(cudaMalloc(reinterpret_cast<void**>(&d_record), sizeof(SbtRecordType)));
    CUDA_CHECK(cudaMemcpy(
        reinterpret_cast<void*>(d_record),
        &sbt_record,
        sizeof(SbtRecordType),
        cudaMemcpyHostToDevice
    ));

    return d_record;
}

CUdeviceptr OptiXContext::createRaygenSBTRecord(OptixProgramGroup program_group, const RayGenData& data) {
    return createSBTRecordHelper(program_group, data);
}

CUdeviceptr OptiXContext::createMissSBTRecord(OptixProgramGroup program_group, const MissData& data) {
    return createSBTRecordHelper(program_group, data);
}

CUdeviceptr OptiXContext::createHitgroupSBTRecord(OptixProgramGroup program_group, const HitGroupData& data) {
    return createSBTRecordHelper(program_group, data);
}

CUdeviceptr OptiXContext::createTriangleHitgroupSBTRecord(OptixProgramGroup program_group, const TriangleHitGroupData& data) {
    return createSBTRecordHelper(program_group, data);
}

void OptiXContext::freeSBTRecord(CUdeviceptr record) {
    if (record) {
        cudaFree(reinterpret_cast<void*>(record));
    }
}

void OptiXContext::launch(
    OptixPipeline pipeline,
    const OptixShaderBindingTable& sbt,
    CUdeviceptr params_buffer,
    unsigned int width,
    unsigned int height)
{
    // GPU tunables via environment variables (Sprint 30.9c)
    // - MENGER_OPTIX_STREAMS: CUDA stream id (default 0). Non-zero enables concurrent
    //   GPU work but requires stream synchronization management.
    // - MENGER_OPTIX_BLOCK_SIZE: reserved for future manual kernel launch tuning.
    //   Currently OptiX manages block dimensions internally via optixLaunch().
    unsigned int stream_id = 0;
    const char* streams_env = std::getenv("MENGER_OPTIX_STREAMS");
    if (streams_env != nullptr) {
        stream_id = static_cast<unsigned int>(std::stoul(streams_env));
    }

    OPTIX_CHECK(optixLaunch(
        pipeline,
        stream_id,
        params_buffer,
        sizeof(BaseParams),
        &sbt,
        width,
        height,
        1 // depth
    ));

    // Wait for GPU to finish
    CUDA_CHECK(cudaDeviceSynchronize());
}
