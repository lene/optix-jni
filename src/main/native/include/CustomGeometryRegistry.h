#ifndef CUSTOM_GEOMETRY_REGISTRY_H
#define CUSTOM_GEOMETRY_REGISTRY_H

#include <vector>
#include <optix.h>
#include "OptiXData.h"  // GeometryType / GEOMETRY_TYPE_COUNT

// Custom-geometry SPI (Sprint 35 Task 1.1a) — runtime geometry-type ids.
//
// Built-in primitives occupy the fixed id range [0, GEOMETRY_TYPE_COUNT) (see
// enum GeometryType). Externally-registered custom primitives (e.g. the 4D
// fractals that will live in menger-geometry) are allocated ids monotonically
// from GEOMETRY_TYPE_COUNT upward. The id doubles as the SBT block index: a
// type's hit records occupy slots [id * STRIDE_RAY_TYPES, ...), and an IAS
// instance of that type carries sbtOffset = id * STRIDE_RAY_TYPES — the same
// generic math the built-ins already use (OptiXWrapper.cpp), now extended past
// the built-in count. Each registration also carries the OptiX program groups
// (primary / shadow / photon) that its SBT records will reference; those are
// filled in by the pipeline in Task 1.1b.
//
// This header is pure host logic (no OptiX calls) so the id allocation is unit
// -testable without a GPU/context.
struct CustomGeometryRegistration {
    int type_id = -1;                        // runtime id, >= GEOMETRY_TYPE_COUNT
    OptixProgramGroup primary = nullptr;     // primary-ray hit group
    OptixProgramGroup shadow  = nullptr;     // shadow-ray hit group
    OptixProgramGroup photon  = nullptr;     // photon/caustics-ray hit group
};

class CustomGeometryRegistry {
public:
    // Allocate the next runtime type id and record its program groups.
    // Returns the allocated id (>= GEOMETRY_TYPE_COUNT).
    int registerGeometry(OptixProgramGroup primary,
                         OptixProgramGroup shadow,
                         OptixProgramGroup photon) {
        CustomGeometryRegistration reg;
        reg.type_id = GEOMETRY_TYPE_COUNT + static_cast<int>(registrations_.size());
        reg.primary = primary;
        reg.shadow  = shadow;
        reg.photon  = photon;
        registrations_.push_back(reg);
        return reg.type_id;
    }

    const std::vector<CustomGeometryRegistration>& registrations() const {
        return registrations_;
    }

    // Total number of geometry types the SBT must hold: built-ins + registered.
    int totalGeometryTypeCount() const {
        return GEOMETRY_TYPE_COUNT + static_cast<int>(registrations_.size());
    }

    bool empty() const { return registrations_.empty(); }

    // Drop all registrations (e.g. on pipeline teardown/rebuild).
    void clear() { registrations_.clear(); }

private:
    std::vector<CustomGeometryRegistration> registrations_;
};

#endif  // CUSTOM_GEOMETRY_REGISTRY_H
