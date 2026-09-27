// Linked into the pipeline only under MENGER_OPTIX_DEBUG=1 (see OptiXDiagnostics.h and
// PipelineManager::createProgramGroups): names the exception that failed a launch instead of
// leaving a bare CUDA 700/719. The launch still fails afterwards; this only reports why.
extern "C" __global__ void __exception__diagnostic() {
    const uint3 idx = optixGetLaunchIndex();
    const int code = optixGetExceptionCode();
    const char* what =
        code == OPTIX_EXCEPTION_CODE_STACK_OVERFLOW        ? "continuation stack overflow" :
        code == OPTIX_EXCEPTION_CODE_TRACE_DEPTH_EXCEEDED  ? "trace depth exceeded" :
                                                             "user exception";
    const char* line = optixGetExceptionLineInfo();
    printf("[OptiX exception] code=%d (%s) launch_index=(%u,%u,%u) at %s\n",
           code, what, idx.x, idx.y, idx.z, line ? line : "(no line info)");
}
