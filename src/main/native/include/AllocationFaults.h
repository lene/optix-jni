#ifndef ALLOCATION_FAULTS_H
#define ALLOCATION_FAULTS_H

// Test-hook scaffolding: the real counter follows in the next commit.
namespace AllocationFaults {
inline void failNext(int /*n*/) {}
inline bool consume() { return false; }
}

#endif // ALLOCATION_FAULTS_H
