#include <gtest/gtest.h>
#include "../include/OptiXErrorChecking.h"

#include <string>
#include <vector>

// Pure message formatting -- no GPU needed.

namespace {

const cudaError_t kLaunchFailure = cudaErrorLaunchFailure;  // 719

std::string lastLine(const std::string& text) {
    return text.substr(text.rfind('\n') + 1);
}

}  // namespace

TEST(FormatCudaError, MatchesTheLongStandingCudaCheckFormat) {
    EXPECT_EQ(formatCudaError("cudaDeviceSynchronize()", kLaunchFailure),
              std::string("CUDA call 'cudaDeviceSynchronize()' failed: ")
                  + cudaGetErrorString(kLaunchFailure) + " (719)");
}

TEST(FormatCudaError, KeepsTheInvalidProgramCounterDiagnosis) {
    const std::string message = formatCudaError("x()", cudaErrorInvalidPc);  // 718
    EXPECT_NE(message.find("(718)\n\nERROR 718 (invalid program counter)"), std::string::npos);
}

TEST(FormatLaunchFailure, WithoutOptixErrorsReturnsTheCudaErrorUnchanged) {
    const std::string cuda = formatCudaError("cudaDeviceSynchronize()", kLaunchFailure);
    EXPECT_EQ(formatLaunchFailure({}, cuda), cuda);
}

TEST(FormatLaunchFailure, ExplainsLocalMemoryExhaustionInPlainLanguageFirst) {
    const std::string cuda = formatCudaError("cudaDeviceSynchronize()", kLaunchFailure);
    const std::string message = formatLaunchFailure(
        {"cblCudaReconfigureLocalMemory failed with out of memory"}, cuda);

    EXPECT_EQ(message.rfind("OptiX render launch failed: the GPU ran out of memory for the "
                            "renderer's per-thread stack.", 0), 0u);
    EXPECT_NE(message.find("Free GPU memory"), std::string::npos);
    EXPECT_NE(message.find("OptiX reported during this launch: "
                           "cblCudaReconfigureLocalMemory failed with out of memory"),
              std::string::npos);
    // The CUDA error stays intact on its own line, so callers matching the
    // "CUDA call '...' failed: ... (code)" format still recognize it.
    EXPECT_EQ(lastLine(message), cuda);
}

TEST(FormatLaunchFailure, QuotesUnrecognizedOptixErrorsWithoutGuessingAtThem) {
    const std::string cuda = formatCudaError("cudaDeviceSynchronize()", kLaunchFailure);
    const std::string message = formatLaunchFailure({"first problem", "second problem"}, cuda);

    EXPECT_EQ(message.rfind("OptiX render launch failed.\n", 0), 0u);
    EXPECT_EQ(message.find("ran out of memory"), std::string::npos);
    EXPECT_NE(message.find("OptiX reported during this launch: first problem"), std::string::npos);
    EXPECT_NE(message.find("OptiX reported during this launch: second problem"), std::string::npos);
    EXPECT_EQ(lastLine(message), cuda);
}
