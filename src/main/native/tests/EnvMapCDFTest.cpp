#include <gtest/gtest.h>
#include "../include/EnvMapCDF.h"

#include <cmath>
#include <vector>
#include <numeric>
#include <utility>

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

//=============================================================================
// Sun exclusion: texels inside a cone get weight 0 (IBL leaves the sun to its own light)
//=============================================================================

namespace {
const int kExW = 64, kExH = 32;

float cosDegrees(float degrees) { return std::cos(degrees * 3.14159265358979f / 180.0f); }

EnvExclusionCone coneAround(float u, float v, float halfAngleDegrees) {
    EnvExclusionCone cone;
    cone.axis = envUvToDir(u, v);
    cone.cos_half_angle = cosDegrees(halfAngleDegrees);
    return cone;
}

// The texel the sampler draws for uniform randoms (u1, u2): the inversion the shader does, the
// first marginal row whose CDF reaches u1, then the first column of that row reaching u2.
std::pair<int, int> drawTexel(const EnvMapCDFData& cdf, int w, int h, float u1, float u2) {
    int row = 0;
    while (row < h - 1 && cdf.marg_cdf[row] < u1) ++row;
    int col = 0;
    while (col < w - 1 && cdf.cond_cdf[row * w + col] < u2) ++col;
    return {col, row};
}
}  // namespace

TEST(EnvMapCDFSunExclusionTest, TheSamplerNeverDrawsAnExcludedTexel) {
    // A bright sun patch in a dim sky: without the exclusion it takes most of the draws.
    auto img = makeUniformImage(kExW, kExH, 0.05f, 0.05f, 0.05f);
    const EnvExclusionCone cone = coneAround(0.6f, 0.4f, 12.0f);
    int excluded = 0;
    for (int y = 0; y < kExH; ++y)
        for (int x = 0; x < kExW; ++x)
            if (envTexelInCone(x, y, kExW, kExH, cone.axis, cone.cos_half_angle)) {
                ++excluded;
                for (int c = 0; c < 3; ++c) img[(y * kExW + x) * 4 + c] = 500.f;
            }
    ASSERT_GT(excluded, 4);
    const auto cdf = computeEnvMapCDF(img.data(), kExW, kExH, cone);
    int draws = 0;
    for (int i = 0; i < 100; ++i)
        for (int j = 0; j < 100; ++j) {
            const auto [col, row] = drawTexel(cdf, kExW, kExH, (i + 0.5f) / 100.f, (j + 0.5f) / 100.f);
            EXPECT_FALSE(envTexelInCone(col, row, kExW, kExH, cone.axis, cone.cos_half_angle))
                << "drew excluded texel " << col << "," << row;
            ++draws;
        }
    EXPECT_EQ(draws, 10000);
}

TEST(EnvMapCDFSunExclusionTest, ExcludedTexelsHaveZeroPdfAndTheRestStillSumToOne) {
    auto img = makeUniformImage(kExW, kExH, 1.f, 1.f, 1.f);
    const EnvExclusionCone cone = coneAround(0.3f, 0.6f, 20.0f);
    const auto cdf = computeEnvMapCDF(img.data(), kExW, kExH, cone);
    float sum = 0.f;
    for (int y = 0; y < kExH; ++y)
        for (int x = 0; x < kExW; ++x) {
            const float p = cdf.pdf[y * kExW + x];
            if (envTexelInCone(x, y, kExW, kExH, cone.axis, cone.cos_half_angle))
                EXPECT_EQ(p, 0.f);
            else
                EXPECT_GT(p, 0.f);
            sum += p;
        }
    EXPECT_NEAR(sum, 1.0f, 1e-4f);
}

TEST(EnvMapCDFSunExclusionTest, TheRemainingTexelsKeepTheirRelativeWeights) {
    auto img = makeUniformImage(kExW, kExH, 1.f, 1.f, 1.f);
    const EnvExclusionCone cone = coneAround(0.3f, 0.6f, 20.0f);
    const auto plain = computeEnvMapCDF(img.data(), kExW, kExH);
    const auto masked = computeEnvMapCDF(img.data(), kExW, kExH, cone);
    // pdf_masked = pdf_plain / (share of the plain pdf outside the cone), the same for every texel
    float outside = 0.f;
    for (int y = 0; y < kExH; ++y)
        for (int x = 0; x < kExW; ++x)
            if (!envTexelInCone(x, y, kExW, kExH, cone.axis, cone.cos_half_angle))
                outside += plain.pdf[y * kExW + x];
    for (int y = 0; y < kExH; ++y)
        for (int x = 0; x < kExW; ++x)
            if (!envTexelInCone(x, y, kExW, kExH, cone.axis, cone.cos_half_angle))
                EXPECT_NEAR(masked.pdf[y * kExW + x], plain.pdf[y * kExW + x] / outside, 1e-6f);
}

TEST(EnvMapCDFSunExclusionTest, ACosineAboveOneChangesNothing) {
    auto img = makeSinglePixelImage(kExW, kExH, 20, 10, 3.f, 2.f, 1.f);
    const auto plain = computeEnvMapCDF(img.data(), kExW, kExH);
    const auto none = computeEnvMapCDF(img.data(), kExW, kExH, EnvExclusionCone{});
    EXPECT_EQ(plain.pdf, none.pdf);
    EXPECT_EQ(plain.marg_cdf, none.marg_cdf);
    EXPECT_EQ(plain.cond_cdf, none.cond_cdf);
}

TEST(EnvMapCDFSunExclusionTest, ExcludingTheOnlyLightGivesTheAllBlackTables) {
    // Nothing is left to draw: the tables are those of an all-black map (uniform CDFs, zero pdf),
    // which the shader's degenerate-sample guard rejects, and the excluded texels have no radiance
    // for the cosine strategy either, so the estimate is zero rather than undefined.
    const EnvExclusionCone cone = coneAround((20 + 0.5f) / kExW, (10 + 0.5f) / kExH, 12.0f);
    auto img = makeSinglePixelImage(kExW, kExH, 20, 10, 10.f, 10.f, 10.f);
    const auto cdf = computeEnvMapCDF(img.data(), kExW, kExH, cone);
    const auto black = computeEnvMapCDF(makeUniformImage(kExW, kExH, 0.f, 0.f, 0.f).data(), kExW, kExH);
    EXPECT_EQ(cdf.marg_cdf, black.marg_cdf);
    EXPECT_EQ(cdf.cond_cdf, black.cond_cdf);
    EXPECT_EQ(cdf.pdf, black.pdf);
}
