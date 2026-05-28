#pragma once

#include <cmath>
#include <vector>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

struct EnvMapCDFData {
    std::vector<float> marg_cdf;  // height elements
    std::vector<float> cond_cdf;  // width * height elements
    std::vector<float> pdf;       // width * height elements
};

// Compute importance-sampling CDFs for an equirectangular RGBA HDR env map.
// rgba: float buffer, width*height*4 RGBA values (R,G,B,A per pixel, row-major).
// Uses Rec.709 luminance and sin-weighting for equirectangular distortion.
// All-black input falls back to a uniform distribution.
inline EnvMapCDFData computeEnvMapCDF(const float* rgba, int width, int height) {
    std::vector<float> wlum(width * height);
    float total = 0.f;
    for (int y = 0; y < height; ++y) {
        const float sin_w =
            sinf((float)M_PI * (y + 0.5f) / static_cast<float>(height));
        for (int x = 0; x < width; ++x) {
            const int   i = (y * width + x) * 4;
            const float l = 0.2126f * rgba[i] + 0.7152f * rgba[i + 1]
                          + 0.0722f * rgba[i + 2];
            wlum[y * width + x] = l * sin_w;
            total += l * sin_w;
        }
    }

    std::vector<float> row_sums(height, 0.f);
    for (int y = 0; y < height; ++y)
        for (int x = 0; x < width; ++x)
            row_sums[y] += wlum[y * width + x];
    float marg_total = 0.f;
    for (float v : row_sums) marg_total += v;

    std::vector<float> marg_cdf(height);
    float mrunning = 0.f;
    for (int y = 0; y < height; ++y) {
        mrunning +=
            (marg_total > 0.f ? row_sums[y] / marg_total : 1.f / height);
        marg_cdf[y] = mrunning;
    }
    marg_cdf[height - 1] = 1.0f;

    std::vector<float> cond_cdf(width * height);
    for (int y = 0; y < height; ++y) {
        const float rt = row_sums[y];
        float crunning = 0.f;
        for (int x = 0; x < width; ++x) {
            crunning +=
                (rt > 0.f ? wlum[y * width + x] / rt : 1.f / width);
            cond_cdf[y * width + x] = crunning;
        }
        cond_cdf[y * width + width - 1] = 1.0f;
    }

    const float inv_total =
        (total > 0.f ? 1.f / total : 1.f / (width * height));
    std::vector<float> pdf(width * height);
    for (int i = 0; i < width * height; ++i)
        pdf[i] = wlum[i] * inv_total;

    return EnvMapCDFData{marg_cdf, cond_cdf, pdf};
}
