#include <gtest/gtest.h>
#include "../include/CustomGeometryRegistry.h"

// Custom-geometry SPI (Sprint 35 Task 1.1a): runtime geometry-type id allocation.
// Pure host logic — no OptiX context needed. Program-group handles are opaque
// pointers here; we only exercise id/count bookkeeping, so nullptr is fine.

TEST(CustomGeometryRegistry, StartsEmptyWithBuiltinCount) {
    CustomGeometryRegistry reg;
    EXPECT_TRUE(reg.empty());
    // With nothing registered, the SBT still holds exactly the built-in types.
    EXPECT_EQ(reg.totalGeometryTypeCount(), static_cast<int>(GEOMETRY_TYPE_COUNT));
}

TEST(CustomGeometryRegistry, AllocatesIdsFromBuiltinCountUpward) {
    CustomGeometryRegistry reg;
    const int first = reg.registerGeometry(nullptr, nullptr, nullptr);
    const int second = reg.registerGeometry(nullptr, nullptr, nullptr);
    const int third = reg.registerGeometry(nullptr, nullptr, nullptr);

    // Runtime ids must not collide with any built-in id and must be monotonic.
    EXPECT_EQ(first, static_cast<int>(GEOMETRY_TYPE_COUNT));
    EXPECT_EQ(second, static_cast<int>(GEOMETRY_TYPE_COUNT) + 1);
    EXPECT_EQ(third, static_cast<int>(GEOMETRY_TYPE_COUNT) + 2);
    EXPECT_GE(first, static_cast<int>(GEOMETRY_TYPE_COUNT));
}

TEST(CustomGeometryRegistry, TotalCountGrowsWithRegistrations) {
    CustomGeometryRegistry reg;
    reg.registerGeometry(nullptr, nullptr, nullptr);
    reg.registerGeometry(nullptr, nullptr, nullptr);
    EXPECT_FALSE(reg.empty());
    EXPECT_EQ(reg.totalGeometryTypeCount(), static_cast<int>(GEOMETRY_TYPE_COUNT) + 2);
    EXPECT_EQ(reg.registrations().size(), 2u);
}

TEST(CustomGeometryRegistry, PreservesProgramGroupHandles) {
    CustomGeometryRegistry reg;
    // Distinct fake handles to confirm they are stored against the right slot.
    auto* pg_primary = reinterpret_cast<OptixProgramGroup>(0x1);
    auto* pg_shadow  = reinterpret_cast<OptixProgramGroup>(0x2);
    auto* pg_photon  = reinterpret_cast<OptixProgramGroup>(0x3);
    const int id = reg.registerGeometry(pg_primary, pg_shadow, pg_photon);

    const auto& regs = reg.registrations();
    ASSERT_EQ(regs.size(), 1u);
    EXPECT_EQ(regs[0].type_id, id);
    EXPECT_EQ(regs[0].primary, pg_primary);
    EXPECT_EQ(regs[0].shadow, pg_shadow);
    EXPECT_EQ(regs[0].photon, pg_photon);
}

TEST(CustomGeometryRegistry, ClearResetsToBuiltinsAndReusesIds) {
    CustomGeometryRegistry reg;
    reg.registerGeometry(nullptr, nullptr, nullptr);
    reg.clear();
    EXPECT_TRUE(reg.empty());
    EXPECT_EQ(reg.totalGeometryTypeCount(), static_cast<int>(GEOMETRY_TYPE_COUNT));
    // After clear the allocator restarts at the built-in count.
    EXPECT_EQ(reg.registerGeometry(nullptr, nullptr, nullptr),
              static_cast<int>(GEOMETRY_TYPE_COUNT));
}
