#ifndef PIPELINE_MANAGER_H
#define PIPELINE_MANAGER_H

#include <optix.h>
#include <cuda_runtime.h>
#include "OptiXContext.h"
#include "SceneParameters.h"
#include "CustomGeometryRegistry.h"

// Forward declarations
struct BaseParams;

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

    // Custom-geometry SPI (Task 1.1b): register an external primitive's hit
    // programs from a caller-provided PTX module (see createModuleFromPTX).
    // Creates the primary/shadow/photon hit groups and allocates a runtime
    // geometry-type id (>= GEOMETRY_TYPE_COUNT), returned to the caller for use
    // as an instance's geometry_type. Pass nullptr for shadow/photon entries to
    // reuse the primary group. Register before the first buildPipeline().
    int registerCustomGeometry(
        OptixModule geomModule,
        const char* isEntry, const char* chEntry,
        const char* shadowChEntry, const char* shadowAhEntry,
        const char* photonChEntry);

    // Task 1.1d: same, but from PTX bytes — creates the module in THIS pipeline's
    // context with the pipeline's compile options (a module made in a foreign
    // context can't link here), then registers it. This is the public SPI entry.
    int registerCustomGeometryFromPTX(
        const char* ptxBytes, size_t ptxSize,
        const char* isEntry, const char* chEntry,
        const char* shadowChEntry, const char* shadowAhEntry,
        const char* photonChEntry);

    // Lightweight camera-only update (avoids full pipeline rebuild)
    void updateCameraInSBT(const SceneParameters& scene);

    // Enable shader execution reordering if GPU supports it
    void setSerEnabled(uint32_t enabled) { ser_enabled_ = enabled; }

    // Accessors
    OptixPipeline getPipeline() const { return pipeline; }
    const OptixShaderBindingTable& getSBT() const { return sbt; }
    CUdeviceptr getParamsBuffer() const { return d_params; }
    OptixProgramGroup getCausticsHitpointsRaygen() const { return caustics_hitpoints_raygen; }
    OptixProgramGroup getCausticsPhotonsRaygen() const { return caustics_photons_raygen; }
    OptixProgramGroup getCausticsRadianceRaygen() const { return caustics_radiance_raygen; }
    OptixProgramGroup getCausticsUpdateRadiiRaygen() const { return caustics_update_radii_raygen; }
    OptixProgramGroup getCausticsGridCountRaygen() const { return caustics_grid_count_raygen; }
    OptixProgramGroup getCausticsGridScatterRaygen() const { return caustics_grid_scatter_raygen; }

    // Temporary SBT for caustics passes
    CUdeviceptr createTempRaygenSBTRecord(OptixProgramGroup raygen, const SceneParameters& scene);
    void freeTempRaygenSBTRecord(CUdeviceptr record);

private:
    OptiXContext& optix_context;

    // Shader execution reordering (SER) configuration
    uint32_t ser_enabled_ = 0;

    // Pipeline resources
    OptixPipeline pipeline = nullptr;
    OptixModule module = nullptr;
    // Custom-geometry SPI (Task 1.1b): externally-registered primitives' runtime
    // ids + program groups. Empty for a pure built-in scene (no behavior change).
    CustomGeometryRegistry custom_geometry_registry;
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

    // Cone program groups
    OptixProgramGroup cone_hitgroup_prog_group = nullptr;
    OptixProgramGroup cone_shadow_hitgroup_prog_group = nullptr;

    // Curve program groups
    OptixProgramGroup curve_hitgroup_prog_group = nullptr;
    OptixProgramGroup curve_shadow_hitgroup_prog_group = nullptr;
    OptixModule curve_module = nullptr;
    OptixModule curve_shadow_module = nullptr;
    OptixModule curve_photon_module = nullptr;

    // Plane program groups
    OptixProgramGroup plane_hitgroup_prog_group = nullptr;
    OptixProgramGroup plane_shadow_hitgroup_prog_group = nullptr;

    // Menger4D program groups
    OptixProgramGroup menger4d_hitgroup_prog_group = nullptr;
    OptixProgramGroup menger4d_shadow_hitgroup_prog_group = nullptr;

    // Sierpinski4D program groups
    OptixProgramGroup sierpinski4d_hitgroup_prog_group = nullptr;
    OptixProgramGroup sierpinski4d_shadow_hitgroup_prog_group = nullptr;
    OptixProgramGroup photon_sierpinski4d_hitgroup = nullptr;

    // Hexadecachoron4D program groups
    OptixProgramGroup hexadecachoron4d_hitgroup_prog_group = nullptr;
    OptixProgramGroup hexadecachoron4d_shadow_hitgroup_prog_group = nullptr;
    OptixProgramGroup photon_hexadecachoron4d_hitgroup = nullptr;

    // Photon ray program groups (for caustics RAY_TYPE_PHOTON)
    OptixProgramGroup photon_sphere_hitgroup = nullptr;
    OptixProgramGroup photon_triangle_hitgroup = nullptr;
    OptixProgramGroup photon_cylinder_hitgroup = nullptr;
    OptixProgramGroup photon_cone_hitgroup = nullptr;
    OptixProgramGroup photon_curve_hitgroup = nullptr;
    OptixProgramGroup photon_plane_hitgroup = nullptr;
    OptixProgramGroup photon_menger4d_hitgroup = nullptr;
    OptixProgramGroup photon_miss_prog_group = nullptr;

    // Caustics program groups
    OptixProgramGroup caustics_hitpoints_raygen = nullptr;
    OptixProgramGroup caustics_photons_raygen = nullptr;
    OptixProgramGroup caustics_radiance_raygen = nullptr;
    OptixProgramGroup caustics_update_radii_raygen = nullptr;
    OptixProgramGroup caustics_grid_count_raygen = nullptr;
    OptixProgramGroup caustics_grid_scatter_raygen = nullptr;

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
