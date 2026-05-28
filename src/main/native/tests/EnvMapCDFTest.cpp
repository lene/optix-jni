#include <gtest/gtest.h>
#include "../include/EnvMapCDF.h"

#include <cmath>
#include <vector>
#include <numeric>

// Build a flat RGBA image: all pixels set to (r, g, b, 1.0)
static std::vector<float> makeUniformImage(int w, int h, float r, float g, float b) {
    std::vector<float> buf(w * h * 4);
    for (int i = 0; i < w * h; ++i) {
        buf[i * 4 + 0] = r;
        buf[i * 4 + 1] = g;
        buf[i * 4 + 2] = b;
        buf[i * 4 + 3] = 1.f;
    }
    return buf;
}

// Build an RGBA image with a single bright pixel at (px, py), all others black.
static std::vector<float> makeSinglePixelImage(
    int w, int h, int px, int py, float r, float g, float b) {
    std::vector<float> buf(w * h * 4, 0.f);
    const int i = (py * w + px) * 4;
    buf[i + 0] = r;
    buf[i + 1] = g;
    buf[i + 2] = b;
    buf[i + 3] = 1.f;
    return buf;
}

//=============================================================================
// Structural invariants
//=============================================================================

TEST(EnvMapCDFTest, MarginalCDFEndsAtOne) {
    const int W = 16, H = 8;
    auto img = makeUniformImage(W, H, 1.f, 1.f, 1.f);
    auto cdf = computeEnvMapCDF(img.data(), W, H);
    EXPECT_NEAR(cdf.marg_cdf.back(), 1.0f, 1e-5f);
}

TEST(EnvMapCDFTest, MarginalCDFIsMonotone) {
    const int W = 16, H = 8;
    auto img = makeUniformImage(W, H, 1.f, 0.5f, 0.2f);
    auto cdf = computeEnvMapCDF(img.data(), W, H);
    for (int y = 1; y < H; ++y)
        EXPECT_GE(cdf.marg_cdf[y], cdf.marg_cdf[y - 1]);
}

TEST(EnvMapCDFTest, ConditionalCDFEndsAtOneForEveryRow) {
    const int W = 8, H = 4;
    auto img = makeUniformImage(W, H, 0.8f, 0.3f, 0.1f);
    auto cdf = computeEnvMapCDF(img.data(), W, H);
    for (int y = 0; y < H; ++y)
        EXPECT_NEAR(cdf.cond_cdf[y * W + W - 1], 1.0f, 1e-5f);
}

TEST(EnvMapCDFTest, ConditionalCDFIsMonotonePerRow) {
    const int W = 8, H = 4;
    auto img = makeUniformImage(W, H, 0.8f, 0.3f, 0.1f);
    auto cdf = computeEnvMapCDF(img.data(), W, H);
    for (int y = 0; y < H; ++y)
        for (int x = 1; x < W; ++x)
            EXPECT_GE(cdf.cond_cdf[y * W + x], cdf.cond_cdf[y * W + x - 1]);
}

TEST(EnvMapCDFTest, PDFSumsToOne) {
    const int W = 16, H = 8;
    auto img = makeUniformImage(W, H, 1.f, 1.f, 1.f);
    auto cdf = computeEnvMapCDF(img.data(), W, H);
    float sum = std::accumulate(cdf.pdf.begin(), cdf.pdf.end(), 0.f);
    EXPECT_NEAR(sum, 1.0f, 1e-4f);
}

TEST(EnvMapCDFTest, OutputSizesMatchDimensions) {
    const int W = 12, H = 6;
    auto img = makeUniformImage(W, H, 1.f, 1.f, 1.f);
    auto cdf = computeEnvMapCDF(img.data(), W, H);
    EXPECT_EQ((int)cdf.marg_cdf.size(), H);
    EXPECT_EQ((int)cdf.cond_cdf.size(), W * H);
    EXPECT_EQ((int)cdf.pdf.size(),      W * H);
}

//=============================================================================
// Semantic correctness
//=============================================================================

TEST(EnvMapCDFTest, AllBlackImageFallsBackToUniform) {
    const int W = 4, H = 4;
    std::vector<float> img(W * H * 4, 0.f);
    auto cdf = computeEnvMapCDF(img.data(), W, H);

    // Marginal CDF should be perfectly linear (1/H steps)
    for (int y = 0; y < H; ++y)
        EXPECT_NEAR(cdf.marg_cdf[y], (y + 1) / (float)H, 1e-5f);

    // Each conditional CDF should be perfectly linear (1/W steps)
    for (int y = 0; y < H; ++y)
        for (int x = 0; x < W; ++x)
            EXPECT_NEAR(cdf.cond_cdf[y * W + x], (x + 1) / (float)W, 1e-5f);
}

TEST(EnvMapCDFTest, BrightTopRow_MarginalCDFJumpsAtFirstRow) {
    // All luminance is in row 0 → marginal CDF[0] should be 1.0
    const int W = 4, H = 4;
    std::vector<float> img(W * H * 4, 0.f);
    for (int x = 0; x < W; ++x) {
        img[(0 * W + x) * 4 + 0] = 1.f;  // R in row 0
        img[(0 * W + x) * 4 + 3] = 1.f;
    }
    auto cdf = computeEnvMapCDF(img.data(), W, H);
    EXPECT_NEAR(cdf.marg_cdf[0], 1.0f, 1e-5f);
    // Remaining rows add nothing
    for (int y = 1; y < H; ++y)
        EXPECT_NEAR(cdf.marg_cdf[y], 1.0f, 1e-5f);
}

TEST(EnvMapCDFTest, BrightSinglePixel_ConditionalCDFJumpsAtPixelColumn) {
    const int W = 8, H = 4;
    const int BX = 5, BY = 2;
    auto img = makeSinglePixelImage(W, H, BX, BY, 1.f, 1.f, 1.f);
    auto cdf = computeEnvMapCDF(img.data(), W, H);

    // For the bright row (BY), CDF before BX should be 0, at BX should be 1
    for (int x = 0; x < BX; ++x)
        EXPECT_NEAR(cdf.cond_cdf[BY * W + x], 0.0f, 1e-5f);
    EXPECT_NEAR(cdf.cond_cdf[BY * W + BX], 1.0f, 1e-5f);
}

TEST(EnvMapCDFTest, BrightSinglePixel_PDFPeaksAtBrightPixel) {
    const int W = 8, H = 4;
    const int BX = 3, BY = 1;
    auto img = makeSinglePixelImage(W, H, BX, BY, 1.f, 1.f, 1.f);
    auto cdf = computeEnvMapCDF(img.data(), W, H);

    // Only the bright pixel has non-zero PDF
    for (int i = 0; i < W * H; ++i) {
        if (i == BY * W + BX)
            EXPECT_GT(cdf.pdf[i], 0.0f);
        else
            EXPECT_NEAR(cdf.pdf[i], 0.0f, 1e-6f);
    }
}
