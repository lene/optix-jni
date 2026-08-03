#include <gtest/gtest.h>
#include "MaterialPayload.h"

// Fitness function for Sprint 35 Task 3.1 / F2. The material crosses JNI as a packed float[]
// unpacked once by readMaterialPayload(); this pins the C++ layout so a resize/reorder can't
// silently diverge from io.github.lene.optix.MaterialPayload (Scala side, MaterialPayloadSuite).
// A field added on one side without MATERIAL_PAYLOAD_FLOAT_COUNT / MaterialPayload.FloatCount
// following breaks this test, and readMaterialPayload rejects a wrong-length array at runtime.

TEST(MaterialPayloadTest, FloatCountMatchesStructSize) {
    EXPECT_EQ(sizeof(MaterialPayload), MATERIAL_PAYLOAD_FLOAT_COUNT * sizeof(float));
}

TEST(MaterialPayloadTest, LayoutIsTwelveTightlyPackedFloats) {
    // The JVM (MaterialPayload.FloatCount) packs exactly this many floats, in order.
    EXPECT_EQ(MATERIAL_PAYLOAD_FLOAT_COUNT, 12);
    static_assert(sizeof(MaterialPayload) == 12 * sizeof(float),
                  "MaterialPayload must stay 12 tightly-packed floats (no padding, no reorder)");
}
