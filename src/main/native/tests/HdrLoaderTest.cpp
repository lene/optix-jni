#include <gtest/gtest.h>
#include "../include/HdrLoader.h"

#include <sys/resource.h>

#include <chrono>
#include <cstdint>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <random>
#include <string>
#include <vector>

namespace fs = std::filesystem;

// Largest width or height stb_image accepts (STBI_MAX_DIMENSIONS in stb_image_impl.cpp).
constexpr int MAX_DIMENSION = 8192;

namespace {

// Writes `bytes` to `path`.
void writeBytes(const fs::path& path, const std::vector<unsigned char>& bytes) {
    std::ofstream out(path, std::ios::binary);
    out.write(reinterpret_cast<const char*>(bytes.data()), static_cast<std::streamsize>(bytes.size()));
}

// Header of a flat (not run-length encoded) Radiance file, as TestUtilities.writeHdr writes it.
std::vector<unsigned char> hdrHeader(int width, int height) {
    const std::string header = "#?RADIANCE\nFORMAT=32-bit_rle_rgbe\n\n-Y " + std::to_string(height) +
                               " +X " + std::to_string(width) + "\n";
    return {header.begin(), header.end()};
}

// RGBE bytes of pixel (x, y): r = 10 + x%200, g = 20 + y%200, b = 30, exponent byte 129, so the
// channels are about mantissa / 128. A pixel never starts with the bytes 2,2 (the run-length marker).
std::vector<unsigned char> pixelBytes(int x, int y) {
    return {static_cast<unsigned char>(10 + x % 200), static_cast<unsigned char>(20 + y % 200), 30, 129};
}

std::vector<unsigned char> validHdr(int width, int height) {
    auto bytes = hdrHeader(width, height);
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            const auto pixel = pixelBytes(x, y);
            bytes.insert(bytes.end(), pixel.begin(), pixel.end());
        }
    }
    return bytes;
}

long peakResidentKiB() {
    rusage usage{};
    getrusage(RUSAGE_SELF, &usage);
    return usage.ru_maxrss;
}

}  // namespace

class HdrLoaderTest : public ::testing::Test {
protected:
    void SetUp() override {
        std::string pattern = (fs::temp_directory_path() / "hdr_loader_test_XXXXXX").string();
        ASSERT_NE(mkdtemp(pattern.data()), nullptr);
        dir = pattern;
    }

    // Removes only the directory this test created, with the files it wrote.
    void TearDown() override { fs::remove_all(dir); }

    fs::path write(const std::string& name, const std::vector<unsigned char>& bytes) {
        const fs::path path = dir / name;
        writeBytes(path, bytes);
        return path;
    }

    std::optional<HdrImage> load(const fs::path& path) {
        error.clear();
        return loadHdr(path.c_str(), error);
    }

    void expectRejected(const fs::path& path) {
        EXPECT_FALSE(load(path).has_value());
        EXPECT_FALSE(error.empty());
    }

    fs::path dir;
    std::string error;
};

TEST_F(HdrLoaderTest, ValidFileLoadsWithSizeAndPixelValue) {
    const auto image = load(write("valid.hdr", validHdr(64, 32)));
    ASSERT_TRUE(image.has_value()) << error;
    EXPECT_EQ(image->width, 64);
    EXPECT_EQ(image->height, 32);
    ASSERT_EQ(image->rgba.size(), 64u * 32u * 4u);
    // Pixel (5, 7) has bytes 15, 27, 30, 129; stb decodes a mantissa byte m as (m + 0.5) / 128.
    const size_t i = (7 * 64 + 5) * 4;
    EXPECT_FLOAT_EQ(image->rgba[i + 0], 15.5f / 128.0f);
    EXPECT_FLOAT_EQ(image->rgba[i + 1], 27.5f / 128.0f);
    EXPECT_FLOAT_EQ(image->rgba[i + 2], 30.5f / 128.0f);
    EXPECT_FLOAT_EQ(image->rgba[i + 3], 1.0f);
}

TEST_F(HdrLoaderTest, ValidFilesOfEveryAlignmentToTheReadBufferLoad) {
    // stb reads in 128-byte chunks: a complete file must never be flagged as truncated, whatever
    // its length modulo the chunk size.
    for (int width = 1; width <= 40; ++width) {
        const auto image = load(write("w" + std::to_string(width) + ".hdr", validHdr(width, 1)));
        EXPECT_TRUE(image.has_value()) << "width " << width << ": " << error;
    }
}

TEST_F(HdrLoaderTest, FileCutToHalfItsLengthIsRejected) {
    auto bytes = validHdr(64, 32);
    bytes.resize(bytes.size() / 2);
    expectRejected(write("half.hdr", bytes));
}

TEST_F(HdrLoaderTest, FileCutByOneByteIsRejected) {
    auto bytes = validHdr(64, 32);
    bytes.pop_back();
    expectRejected(write("minus_one.hdr", bytes));
}

TEST_F(HdrLoaderTest, HeaderClaimingHugeSizeWithNoDataIsRejectedQuicklyWithoutLargeAllocation) {
    const auto path = write("huge.hdr", hdrHeader(100000, 100000));
    const long peakBefore = peakResidentKiB();
    const auto start = std::chrono::steady_clock::now();
    expectRejected(path);
    const auto elapsed = std::chrono::steady_clock::now() - start;
    EXPECT_LT(elapsed, std::chrono::seconds(1));
    EXPECT_LT(peakResidentKiB() - peakBefore, 64 * 1024) << "peak resident memory grew by 64 MiB or more";
}

TEST_F(HdrLoaderTest, OneTooManyPixelsWideIsRejected) {
    expectRejected(write("too_wide.hdr", hdrHeader(MAX_DIMENSION + 1, 1)));
}

TEST_F(HdrLoaderTest, OneTooManyPixelsHighIsRejected) {
    expectRejected(write("too_high.hdr", hdrHeader(1, MAX_DIMENSION + 1)));
}

TEST_F(HdrLoaderTest, ExactlyMaximumWidthWithItsDataLoads) {
    const auto image = load(write("max_width.hdr", validHdr(MAX_DIMENSION, 1)));
    ASSERT_TRUE(image.has_value()) << error;
    EXPECT_EQ(image->width, MAX_DIMENSION);
    EXPECT_EQ(image->height, 1);
}

TEST_F(HdrLoaderTest, EmptyFileIsRejected) {
    expectRejected(write("empty.hdr", {}));
}

TEST_F(HdrLoaderTest, RandomBytesAreRejected) {
    std::mt19937 random(12345);
    std::vector<unsigned char> bytes(1024);
    for (auto& b : bytes) b = static_cast<unsigned char>(random());
    expectRejected(write("random.hdr", bytes));
}

TEST_F(HdrLoaderTest, NonexistentPathIsRejected) {
    expectRejected(dir / "does_not_exist.hdr");
}

TEST_F(HdrLoaderTest, NullPathIsRejected) {
    error.clear();
    EXPECT_FALSE(loadHdr(nullptr, error).has_value());
    EXPECT_FALSE(error.empty());
}
