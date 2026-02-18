#ifndef PIPELINE_MANAGER_H
#define PIPELINE_MANAGER_H

#include <optix.h>
#include <cuda_runtime.h>
#include "OptiXContext.h"
#include "SceneParameters.h"

// Forward declarations
struct Params;

/**
 * Manages OptiX pipeline resources and Shader Binding Table (SBT).
 * Handles module loading, program group creation, pipeline linking, and SBT setup.
 *
 * Responsibilities:
 * - Load PTX modules from disk
 * - Create program groups (raygen, miss, hitgroup, caustics)
 * - Link pipeline with proper compilation options
 * - Build and update Shader Binding Table
 * - Manage pipeline lifecycle (build, rebuild, cleanup)
 */
class PipelineManager {
public:
    explicit PipelineManager(OptiXContext& context);
    ~PipelineManager();

    // Pipeline build/rebuild
    void buildPipeline(const SceneParameters& scene, OptixTraversableHandle gasHandle);
    void cleanup(bool includeCaustics);

    // Lightweight camera-only update (avoids full pipeline rebuild)
    void updateCameraInSBT(const SceneParameters& scene);

    // Accessors
    OptixPipeline getPipeline() const { return pipeline; }
    const OptixShaderBindingTable& getSBT() const { return sbt; }
    CUdeviceptr getParamsBuffer() const { return d_params; }
    OptixProgramGroup getCausticsHitpointsRaygen() const { return caustics_hitpoints_raygen; }
    OptixProgramGroup getCausticsPhotonsRaygen() const { return caustics_photons_raygen; }
    OptixProgramGroup getCausticsRadianceRaygen() const { return caustics_radiance_raygen; }

    // Temporary SBT for caustics passes
    CUdeviceptr createTempRaygenSBTRecord(OptixProgramGroup raygen, const SceneParameters& scene);
    void freeTempRaygenSBTRecord(CUdeviceptr record);

private:
    OptiXContext& optix_context;

    // Pipeline resources
    OptixPipeline pipeline = nullptr;
    OptixModule module = nullptr;
    OptixProgramGroup raygen_prog_group = nullptr;
    OptixProgramGroup miss_prog_group = nullptr;
    OptixProgramGroup hitgroup_prog_group = nullptr;
    OptixProgramGroup shadow_miss_prog_group = nullptr;
    OptixProgramGroup shadow_hitgroup_prog_group = nullptr;

    // Triangle mesh program groups
    OptixProgramGroup triangle_hitgroup_prog_group = nullptr;
    OptixProgramGroup triangle_shadow_hitgroup_prog_group = nullptr;

    // Cylinder program groups
    OptixProgramGroup cylinder_hitgroup_prog_group = nullptr;
    OptixProgramGroup cylinder_shadow_hitgroup_prog_group = nullptr;
    OptixModule cylinder_module = nullptr;

    // Caustics program groups
    OptixProgramGroup caustics_hitpoints_raygen = nullptr;
    OptixProgramGroup caustics_photons_raygen = nullptr;
    OptixProgramGroup caustics_radiance_raygen = nullptr;

    // Shader Binding Table and params buffer
    OptixShaderBindingTable sbt = {};
    CUdeviceptr d_params = 0;

    // Pipeline build steps
    OptixModule loadPTXModules();
    void createProgramGroups();
    void createPipeline();
    void setupShaderBindingTable(const SceneParameters& scene, OptixTraversableHandle gasHandle);

    // Helper methods
    void destroyProgramGroupIfExists(OptixProgramGroup& prog_group);

    // SBT section builders (called from setupShaderBindingTable)
    void createRaygenRecord(const SceneParameters& scene);
    void createMissRecords();
    void createHitgroupRecords(const SceneParameters& scene);
};

#endif // PIPELINE_MANAGER_H
