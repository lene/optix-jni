#pragma once

#include <stdexcept>

// A sticky CUDA error made this process's CUDA context unusable (optix-jni#73; see
// OptiXErrorChecking.h). Its own header, without the CUDA headers, so the JNI layer can map it
// to OptiXContextLostException.
class CudaContextLost : public std::runtime_error {
public:
    using std::runtime_error::runtime_error;
};
