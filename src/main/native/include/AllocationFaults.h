#ifndef ALLOCATION_FAULTS_H
#define ALLOCATION_FAULTS_H

#include <atomic>
#include <stdexcept>
#include "OptiXErrorChecking.h"

/**
 * Fault injection for device allocations, for tests only (plan 07-C1). A real out-of-memory is
 * not reproducible on a shared GPU, so the allocations the free-before-allocate fixes touch go
 * through cudaMallocChecked, and a test makes the n-th one fail.
 *
 * The failure is thrown before any CUDA call, as a plain std::runtime_error: it never latches the
 * context as lost.
 */
namespace AllocationFaults {

inline std::atomic<int>& remaining() {
    static std::atomic<int> counter{0};
    return counter;
}

/** The n-th allocation from now on fails, once; n = 1 is the next one, 0 disarms. */
inline void failNext(int n) { remaining().store(n < 0 ? 0 : n); }

/** True for exactly the allocation the armed counter was counting down to. */
inline bool consume() {
    int left = remaining().load();
    while (left > 0 && !remaining().compare_exchange_weak(left, left - 1)) {}
    return left == 1;
}

}  // namespace AllocationFaults

/** cudaMalloc, except that an armed AllocationFaults counter makes it throw instead. */
inline void cudaMallocChecked(void** ptr, size_t bytes) {
    if (AllocationFaults::consume()) {
        throw std::runtime_error("injected allocation failure");
    }
    CUDA_CHECK(cudaMalloc(ptr, bytes));
}

#endif // ALLOCATION_FAULTS_H
