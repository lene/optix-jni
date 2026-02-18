#include "include/PipelineManager.h"
#include "include/OptiXData.h"
#include "include/OptiXConstants.h"
#include "include/OptiXErrorChecking.h"
#include "include/OptiXFileUtils.h"
#include <vector>
#include <cstring>
#include <iostream>
#include <optix_stubs.h>

// Helper to create default pipeline compile options
// Supports both custom primitives (sphere) and built-in triangles
static OptixPipelineCompileOptions getDefaultPipelineCompileOptions() {
    OptixPipelineCompileOptions options = {};
    options.usesMotionBlur = false;
    // Allow both single GAS (backward compatible) and single-level instancing (IAS)
    options.traversableGraphFlags = OPTIX_TRAVERSABLE_GRAPH_FLAG_ALLOW_SINGLE_GAS |
                                    OPTIX_TRAVERSABLE_GRAPH_FLAG_ALLOW_SINGLE_LEVEL_INSTANCING;
    options.numPayloadValues = 4;  // RGB color + depth (optixSetPayload_0/1/2/3)
    options.numAttributeValues = 4;  // Normal x, y, z + radius from SDK intersection
    options.exceptionFlags = OPTIX_EXCEPTION_FLAG_NONE;
    options.pipelineLaunchParamsVariableName = "params";
    // Support both custom primitives (sphere) and built-in triangles
    options.usesPrimitiveTypeFlags = OPTIX_PRIMITIVE_TYPE_FLAGS_CUSTOM | OPTIX_PRIMITIVE_TYPE_FLAGS_TRIANGLE;
    return options;
}

PipelineManager::PipelineManager(OptiXContext& context)
    : optix_context(context) {
}

PipelineManager::~PipelineManager() {
    cleanup(true);
}

OptixModule PipelineManager::loadPTXModules() {
    // Try multiple PTX locations in priority order:
    // 1. Working directory (for packaged app after OptiXRenderer extracts from JAR)
    // 2. Build output directory (for sbt run / sbt test)
    // 3. Classes directory (for IntelliJ/IDE runs)
    std::vector<std::string> ptx_search_paths = {
        "target/native/x86_64-linux/bin/optix_shaders.ptx",  // Extracted from JAR
        "optix-jni/target/native/x86_64-linux/bin/optix_shaders.ptx",  // sbt build output
        "optix-jni/target/classes/native/x86_64-linux/optix_shaders.ptx"  // sbt-jni managed
    };
    std::string ptx_content = optix_utils::readPTXFile(ptx_search_paths);

    OptixModuleCompileOptions module_compile_options = {};
    module_compile_options.maxRegisterCount = OPTIX_COMPILE_DEFAULT_MAX_REGISTER_COUNT;
    module_compile_options.optLevel = OPTIX_COMPILE_OPTIMIZATION_DEFAULT;
    module_compile_options.debugLevel = OPTIX_COMPILE_DEBUG_LEVEL_MINIMAL;

    OptixPipelineCompileOptions pipeline_compile_options = getDefaultPipelineCompileOptions();

    // Use OptiXContext to create module
    module = optix_context.createModuleFromPTX(
        ptx_content,
        module_compile_options,
        pipeline_compile_options
    );

    // Cylinder programs are now included in the main module via hit_cylinder.cu
    cylinder_module = module;

    return module;
}

void PipelineManager::createProgramGroups() {
    // Use OptiXContext to create program groups
    raygen_prog_group = optix_context.createRaygenProgramGroup(
        module, "__raygen__rg"
    );

    // Primary ray miss program
    miss_prog_group = optix_context.createMissProgramGroup(
        module, "__miss__ms"
    );

    // Shadow ray miss program
    shadow_miss_prog_group = optix_context.createMissProgramGroup(
        module, "__miss__shadow"
    );

    // Primary ray hit group (closest hit + intersection)
    hitgroup_prog_group = optix_context.createHitgroupProgramGroup(
        module, "__closesthit__ch",
        module, "__intersection__sphere"
    );

    // Shadow ray hit group (only closest hit, same intersection program)
    shadow_hitgroup_prog_group = optix_context.createHitgroupProgramGroup(
        module, "__closesthit__shadow",
        module, "__intersection__sphere"
    );

    // Triangle mesh hit groups (use built-in triangle intersection)
    triangle_hitgroup_prog_group = optix_context.createTriangleHitgroupProgramGroup(
        module, "__closesthit__triangle"
    );

    triangle_shadow_hitgroup_prog_group = optix_context.createTriangleHitgroupProgramGroup(
        module, "__closesthit__triangle_shadow"
    );

    // Cylinder hit groups (custom intersection from cylinder module)
    // NOTE: Anyhit programs temporarily disabled to diagnose rotation crash
    cylinder_hitgroup_prog_group = optix_context.createHitgroupProgramGroup(
        cylinder_module, "__closesthit__cylinder",
        cylinder_module, "__intersection__cylinder"
    );

    cylinder_shadow_hitgroup_prog_group = optix_context.createHitgroupProgramGroup(
        cylinder_module, "__closesthit__cylinder_shadow",
        cylinder_module, "__intersection__cylinder"
    );

    // Caustics raygen programs (for Progressive Photon Mapping)
    caustics_hitpoints_raygen = optix_context.createRaygenProgramGroup(
        module, "__raygen__hitpoints"
    );
    caustics_photons_raygen = optix_context.createRaygenProgramGroup(
        module, "__raygen__photons"
    );
    caustics_radiance_raygen = optix_context.createRaygenProgramGroup(
        module, "__raygen__caustics_radiance"
    );
}

void PipelineManager::createPipeline() {
    constexpr int NUM_PROGRAM_GROUPS = 12;  // raygen(1) + miss(2) + hitgroups: sphere(2)+tri(2)+cylinder(2)+caustics(3)
    OptixProgramGroup program_groups[] = {
        raygen_prog_group,
        miss_prog_group,
        hitgroup_prog_group,
        shadow_miss_prog_group,
        shadow_hitgroup_prog_group,
        triangle_hitgroup_prog_group,
        triangle_shadow_hitgroup_prog_group,
        cylinder_hitgroup_prog_group,
        cylinder_shadow_hitgroup_prog_group,
        caustics_hitpoints_raygen,
        caustics_photons_raygen,
        caustics_radiance_raygen
    };

    OptixPipelineCompileOptions pipeline_compile_options = getDefaultPipelineCompileOptions();

    OptixPipelineLinkOptions pipeline_link_options = {};
    pipeline_link_options.maxTraceDepth = MAX_TRACE_DEPTH;  // Defined in OptiXData.h

    // Use OptiXContext to create pipeline
    pipeline = optix_context.createPipeline(
        pipeline_compile_options,
        pipeline_link_options,
        program_groups,
        NUM_PROGRAM_GROUPS
    );
}

void PipelineManager::setupShaderBindingTable(const SceneParameters& scene, OptixTraversableHandle gasHandle) {
    // Clean up old SBT records if they exist (for pipeline rebuild)
    if (sbt.raygenRecord) {
        optix_context.freeSBTRecord(sbt.raygenRecord);
        sbt.raygenRecord = 0;
    }
    if (sbt.missRecordBase) {
        optix_context.freeSBTRecord(sbt.missRecordBase);
        sbt.missRecordBase = 0;
    }
    if (sbt.hitgroupRecordBase) {
        optix_context.freeSBTRecord(sbt.hitgroupRecordBase);
        sbt.hitgroupRecordBase = 0;
    }

    // Ray generation record data
    const auto& camera = scene.getCamera();
    RayGenData rg_data;
    std::memcpy(rg_data.cam_eye, camera.eye, sizeof(float) * 3);
    std::memcpy(rg_data.camera_u, camera.u, sizeof(float) * 3);
    std::memcpy(rg_data.camera_v, camera.v, sizeof(float) * 3);
    std::memcpy(rg_data.camera_w, camera.w, sizeof(float) * 3);

    sbt.raygenRecord = optix_context.createRaygenSBTRecord(
        raygen_prog_group,
        rg_data
    );

    // Miss records: [0] = primary ray miss, [1] = shadow ray miss
    MissData ms_data;
    ms_data.r = OptiXConstants::DEFAULT_BG_R;
    ms_data.g = OptiXConstants::DEFAULT_BG_G;
    ms_data.b = OptiXConstants::DEFAULT_BG_B;

    // Allocate array for 2 miss records
    MissSbtRecord miss_records[2];
    optixSbtRecordPackHeader(miss_prog_group, &miss_records[0]);
    miss_records[0].data = ms_data;

    // Shadow miss has no data (just marks as not occluded)
    optixSbtRecordPackHeader(shadow_miss_prog_group, &miss_records[1]);
    miss_records[1].data = ms_data;  // Reuse same data (not used by shadow miss)

    CUdeviceptr d_miss_records;
    CUDA_CHECK(cudaMalloc(reinterpret_cast<void**>(&d_miss_records), sizeof(miss_records)));
    CUDA_CHECK(cudaMemcpy(
        reinterpret_cast<void*>(d_miss_records),
        miss_records,
        sizeof(miss_records),
        cudaMemcpyHostToDevice
    ));
    sbt.missRecordBase = d_miss_records;
    sbt.missRecordStrideInBytes = sizeof(MissSbtRecord);
    sbt.missRecordCount = 2;

    // Hit group records for IAS mode need to support sphere, triangle, and cylinder geometry types
    // SBT layout: [0]=sphere_primary, [1]=sphere_shadow, [2]=triangle_primary, [3]=triangle_shadow,
    //             [4]=cylinder_primary, [5]=cylinder_shadow
    // Offset calculation: geometry_type * 2 + ray_type (0=primary, 1=shadow)

    // For IAS mode: always build unified 6-record SBT
    // For single-object mode: build 2-record SBT (backward compatible)

    // Maximum record size to ensure proper alignment
    constexpr size_t record_size = std::max(sizeof(HitGroupSbtRecord), sizeof(TriangleHitGroupSbtRecord));

    // Allocate 6 records for IAS mode (3 geometry types * 2 ray types)
    constexpr int num_records = 6;  // 3 geometry types * 2 ray types
    char hitgroup_records[num_records * record_size];
    std::memset(hitgroup_records, 0, sizeof(hitgroup_records));

    // Build sphere hitgroup records [0]=primary, [1]=shadow
    const auto& sphere = scene.getSphere();
    HitGroupData sphere_data;
    std::memcpy(sphere_data.sphere_center, sphere.center, sizeof(float) * 3);
    sphere_data.sphere_radius = sphere.radius;

    HitGroupSbtRecord* sphere_primary = reinterpret_cast<HitGroupSbtRecord*>(hitgroup_records + 0 * record_size);
    optixSbtRecordPackHeader(hitgroup_prog_group, sphere_primary);
    sphere_primary->data = sphere_data;

    HitGroupSbtRecord* sphere_shadow = reinterpret_cast<HitGroupSbtRecord*>(hitgroup_records + 1 * record_size);
    optixSbtRecordPackHeader(shadow_hitgroup_prog_group, sphere_shadow);
    sphere_shadow->data = sphere_data;

    // Build triangle hitgroup records [2]=primary, [3]=shadow
    if (scene.hasTriangleMesh()) {
        const auto& mesh = scene.getTriangleMesh();
        TriangleHitGroupData tri_data;
        tri_data.vertices = reinterpret_cast<float*>(mesh.d_vertices);
        tri_data.indices = reinterpret_cast<unsigned int*>(mesh.d_indices);
        tri_data.vertex_stride = mesh.vertex_stride;
        std::memcpy(tri_data.color, mesh.color, sizeof(float) * 4);
        tri_data.ior = mesh.ior;

        TriangleHitGroupSbtRecord* tri_primary = reinterpret_cast<TriangleHitGroupSbtRecord*>(hitgroup_records + 2 * record_size);
        optixSbtRecordPackHeader(triangle_hitgroup_prog_group, tri_primary);
        tri_primary->data = tri_data;

        TriangleHitGroupSbtRecord* tri_shadow = reinterpret_cast<TriangleHitGroupSbtRecord*>(hitgroup_records + 3 * record_size);
        optixSbtRecordPackHeader(triangle_shadow_hitgroup_prog_group, tri_shadow);
        tri_shadow->data = tri_data;
    }

    // Build cylinder hitgroup records [4]=primary, [5]=shadow
    // Cylinder data is stored per-instance in the GAS, so SBT record just needs header
    HitGroupSbtRecord* cylinder_primary = reinterpret_cast<HitGroupSbtRecord*>(hitgroup_records + 4 * record_size);
    optixSbtRecordPackHeader(cylinder_hitgroup_prog_group, cylinder_primary);
    cylinder_primary->data = sphere_data;  // Placeholder data (not used by cylinder shader)

    HitGroupSbtRecord* cylinder_shadow = reinterpret_cast<HitGroupSbtRecord*>(hitgroup_records + 5 * record_size);
    optixSbtRecordPackHeader(cylinder_shadow_hitgroup_prog_group, cylinder_shadow);
    cylinder_shadow->data = sphere_data;  // Placeholder data (not used by cylinder shader)

    // Upload unified SBT to GPU
    CUdeviceptr d_hitgroup_records;
    CUDA_CHECK(cudaMalloc(reinterpret_cast<void**>(&d_hitgroup_records), sizeof(hitgroup_records)));
    CUDA_CHECK(cudaMemcpy(
        reinterpret_cast<void*>(d_hitgroup_records),
        hitgroup_records,
        sizeof(hitgroup_records),
        cudaMemcpyHostToDevice
    ));
    sbt.hitgroupRecordBase = d_hitgroup_records;
    sbt.hitgroupRecordStrideInBytes = record_size;
    sbt.hitgroupRecordCount = num_records;
}

void PipelineManager::destroyProgramGroupIfExists(OptixProgramGroup& prog_group) {
    if (prog_group) {
        optix_context.destroyProgramGroup(prog_group);
        prog_group = nullptr;
    }
}

void PipelineManager::cleanup(bool includeCaustics) {
    if (pipeline) {
        optix_context.destroyPipeline(pipeline);
        pipeline = nullptr;
    }

    destroyProgramGroupIfExists(raygen_prog_group);
    destroyProgramGroupIfExists(miss_prog_group);
    destroyProgramGroupIfExists(shadow_miss_prog_group);
    destroyProgramGroupIfExists(hitgroup_prog_group);
    destroyProgramGroupIfExists(shadow_hitgroup_prog_group);
    destroyProgramGroupIfExists(triangle_hitgroup_prog_group);
    destroyProgramGroupIfExists(triangle_shadow_hitgroup_prog_group);
    destroyProgramGroupIfExists(cylinder_hitgroup_prog_group);
    destroyProgramGroupIfExists(cylinder_shadow_hitgroup_prog_group);

    if (includeCaustics) {
        destroyProgramGroupIfExists(caustics_hitpoints_raygen);
        destroyProgramGroupIfExists(caustics_photons_raygen);
        destroyProgramGroupIfExists(caustics_radiance_raygen);
    }

    // Check if cylinder_module is a separate module before destroying module
    bool cylinder_is_separate = (cylinder_module && cylinder_module != module);

    if (module) {
        optix_context.destroyModule(module);
        module = nullptr;
    }

    // Only destroy cylinder_module if it was a separate module
    // Currently cylinder programs are included in the main module, so this is a no-op
    if (cylinder_is_separate) {
        optix_context.destroyModule(cylinder_module);
    }
    cylinder_module = nullptr;

    // Clean up SBT buffers
    if (sbt.raygenRecord) {
        optix_context.freeSBTRecord(sbt.raygenRecord);
        sbt.raygenRecord = 0;
    }
    if (sbt.missRecordBase) {
        optix_context.freeSBTRecord(sbt.missRecordBase);
        sbt.missRecordBase = 0;
    }
    if (sbt.hitgroupRecordBase) {
        optix_context.freeSBTRecord(sbt.hitgroupRecordBase);
        sbt.hitgroupRecordBase = 0;
    }

    // Clean up params buffer
    if (d_params) {
        cudaFree(reinterpret_cast<void*>(d_params));
        d_params = 0;
    }

    // Synchronize CUDA to ensure all operations are complete
    cudaError_t sync_err = cudaDeviceSynchronize();
    if (sync_err != cudaSuccess) {
        std::cerr << "[PipelineManager::cleanup] CUDA synchronization error: " << cudaGetErrorString(sync_err) << std::endl;
    }

    // Clear any pending CUDA errors
    cudaError_t final_err = cudaGetLastError();
    if (final_err != cudaSuccess) {
        std::cerr << "[PipelineManager::cleanup] CUDA error after synchronization: " << cudaGetErrorString(final_err) << std::endl;
    }
}

void PipelineManager::buildPipeline(const SceneParameters& scene, OptixTraversableHandle gasHandle) {
    // Clean up old pipeline resources if they exist (for pipeline rebuild)
    cleanup(false);

    loadPTXModules();
    createProgramGroups();
    createPipeline();
    setupShaderBindingTable(scene, gasHandle);

    // Allocate params buffer (only on first build)
    if (!d_params) {
        CUDA_CHECK(cudaMalloc(reinterpret_cast<void**>(&d_params), sizeof(Params)));
    }
}

void PipelineManager::updateCameraInSBT(const SceneParameters& scene) {
    if (!sbt.raygenRecord) {
        // No SBT record exists yet - need full pipeline build first
        return;
    }

    // Build camera data from scene parameters (same as createTempRaygenSBTRecord)
    const auto& camera = scene.getCamera();
    RayGenData rg_data;
    std::memcpy(rg_data.cam_eye, camera.eye, sizeof(float) * 3);
    std::memcpy(rg_data.camera_u, camera.u, sizeof(float) * 3);
    std::memcpy(rg_data.camera_v, camera.v, sizeof(float) * 3);
    std::memcpy(rg_data.camera_w, camera.w, sizeof(float) * 3);

    // Build complete SBT record (header + data) on host
    // Header is tied to program group but doesn't change between camera updates
    RayGenSbtRecord sbt_record;
    OPTIX_CHECK(optixSbtRecordPackHeader(raygen_prog_group, &sbt_record));
    sbt_record.data = rg_data;

    // Upload to existing GPU memory location (in-place update)
    CUDA_CHECK(cudaMemcpy(
        reinterpret_cast<void*>(sbt.raygenRecord),
        &sbt_record,
        sizeof(RayGenSbtRecord),
        cudaMemcpyHostToDevice
    ));
}

CUdeviceptr PipelineManager::createTempRaygenSBTRecord(OptixProgramGroup raygen, const SceneParameters& scene) {
    const auto& camera = scene.getCamera();
    RayGenData rg_data;
    std::memcpy(rg_data.cam_eye, camera.eye, sizeof(float) * 3);
    std::memcpy(rg_data.camera_u, camera.u, sizeof(float) * 3);
    std::memcpy(rg_data.camera_v, camera.v, sizeof(float) * 3);
    std::memcpy(rg_data.camera_w, camera.w, sizeof(float) * 3);

    return optix_context.createRaygenSBTRecord(raygen, rg_data);
}

void PipelineManager::freeTempRaygenSBTRecord(CUdeviceptr record) {
    optix_context.freeSBTRecord(record);
}
