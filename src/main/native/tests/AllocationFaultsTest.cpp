#include <gtest/gtest.h>
#include "../include/AllocationFaults.h"

// Fault injection for device allocations (plan 07-C1): failNext(n) makes the n-th allocation
// after the call fail, once. Host-only; no GPU call.

namespace {
class AllocationFaultsTest : public ::testing::Test {
protected:
    void SetUp() override { AllocationFaults::failNext(0); }
    void TearDown() override { AllocationFaults::failNext(0); }
};
}  // namespace

TEST_F(AllocationFaultsTest, NothingFailsWhenNotArmed) {
    EXPECT_FALSE(AllocationFaults::consume());
    EXPECT_FALSE(AllocationFaults::consume());
}

TEST_F(AllocationFaultsTest, TheNextAllocationFailsOnceForOne) {
    AllocationFaults::failNext(1);
    EXPECT_TRUE(AllocationFaults::consume());
    EXPECT_FALSE(AllocationFaults::consume());
}

TEST_F(AllocationFaultsTest, TheNthAllocationFailsAndOnlyThat) {
    AllocationFaults::failNext(3);
    EXPECT_FALSE(AllocationFaults::consume());
    EXPECT_FALSE(AllocationFaults::consume());
    EXPECT_TRUE(AllocationFaults::consume());
    EXPECT_FALSE(AllocationFaults::consume());
}

TEST_F(AllocationFaultsTest, ZeroDisarms) {
    AllocationFaults::failNext(2);
    AllocationFaults::failNext(0);
    EXPECT_FALSE(AllocationFaults::consume());
    EXPECT_FALSE(AllocationFaults::consume());
}
