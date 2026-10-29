#pragma once

#include <mutex>
#include <stdexcept>
#include <string>

// A sticky CUDA error made this process's CUDA context unusable (optix-jni#73; see
// OptiXErrorChecking.h). Its own header, without the CUDA headers, so the JNI layer can map it
// to OptiXContextLostException and check the latch at every entry.
class CudaContextLost : public std::runtime_error {
public:
    using std::runtime_error::runtime_error;
};

namespace cuda_state {
inline std::mutex& lostMutex() { static std::mutex m; return m; }
inline std::string& lostReason() { static std::string reason; return reason; }
}  // namespace cuda_state

/** Records `reason` as the context loss, unless one is recorded already (the first wins). */
inline void latchContextLost(const std::string& reason) {
    std::lock_guard<std::mutex> lock(cuda_state::lostMutex());
    if (cuda_state::lostReason().empty()) cuda_state::lostReason() = reason;
}

/** The latched context loss, or "" while the context is fine. */
inline std::string contextLostReason() {
    std::lock_guard<std::mutex> lock(cuda_state::lostMutex());
    return cuda_state::lostReason();
}

inline void throwIfContextLost() {
    const std::string reason = contextLostReason();
    if (!reason.empty()) {
        throw CudaContextLost("CUDA context lost earlier in this process (restart it): " + reason);
    }
}
