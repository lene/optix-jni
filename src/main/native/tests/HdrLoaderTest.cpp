#include <gtest/gtest.h>
#include "../include/HdrLoader.h"

#include "stb_image.h"

#include <chrono>
#include <cstdint>
#include <cstdlib>
#include <filesystem>
#include <functional>
#include <fstream>
#include <random>
#include <string>
#include <vector>

namespace fs = std::filesystem;

namespace {

// How long rejecting a header-only file claiming 8192x8192 may take: it must cost no decoding and
// no allocation. Not asserted under the sanitizers, which slow everything down (CMake sets
// OPTIX_SANITIZE for them; GCC also defines __SANITIZE_ADDRESS__).
constexpr int MAX_REJECT_MILLISECONDS = 100;
#if defined(OPTIX_SANITIZE) || defined(__SANITIZE_ADDRESS__)
constexpr bool TIMING_IS_MEANINGFUL = false;
#else
constexpr bool TIMING_IS_MEANINGFUL = true;
#endif

using Bytes = std::vector<unsigned char>;

void append(Bytes& to, const Bytes& more) {
    to.insert(to.end(), more.begin(), more.end());
}

void writeBytes(const fs::path& path, const Bytes& bytes) {
    std::ofstream out(path, std::ios::binary);
    out.write(reinterpret_cast<const char*>(bytes.data()), static_cast<std::streamsize>(bytes.size()));
}

// Header of a Radiance file, as TestUtilities.writeHdr writes it. `paddingBytes` (0 or at least 2)
// adds a comment line of exactly that many bytes, which the format allows before the blank line.
Bytes hdrHeader(int width, int height, size_t paddingBytes = 0) {
    std::string header = "#?RADIANCE\nFORMAT=32-bit_rle_rgbe\n";
    if (paddingBytes > 0) header += "#" + std::string(paddingBytes - 2, 'x') + "\n";
    header += "\n-Y " + std::to_string(height) + " +X " + std::to_string(width) + "\n";
    return {header.begin(), header.end()};
}

// RGBE bytes of pixel (x, y): r = 10 + x%200, g = 20 + y%200, b = 30, exponent byte 129, so the
// channels are about mantissa / 128. A pixel never starts with the bytes 2,2 (the run-length marker).
Bytes pixelBytes(int x, int y) {
    return {static_cast<unsigned char>(10 + x % 200), static_cast<unsigned char>(20 + y % 200), 30, 129};
}

Bytes flatPixels(int width, int height) {
    Bytes bytes;
    for (int y = 0; y < height; ++y)
        for (int x = 0; x < width; ++x) append(bytes, pixelBytes(x, y));
    return bytes;
}

Bytes validHdr(int width, int height) {
    Bytes bytes = hdrHeader(width, height);
    append(bytes, flatPixels(width, height));
    return bytes;
}

// A flat file of exactly `totalBytes`: the header's comment line takes up the difference.
Bytes flatHdrOfSize(int width, int height, size_t totalBytes) {
    const size_t unpadded = hdrHeader(width, height).size() + flatPixels(width, height).size();
    Bytes bytes = hdrHeader(width, height, totalBytes - unpadded);
    append(bytes, flatPixels(width, height));
    return bytes;
}

// Length of the run of equal bytes starting at `from`, at most `limit`.
size_t runLength(const Bytes& values, size_t from, size_t limit) {
    size_t length = 1;
    while (from + length < values.size() && length < limit && values[from + length] == values[from]) ++length;
    return length;
}

// One channel of a scanline in the format's run-length encoding: runs of 4 to 127 equal bytes
// (count + 128, value) and dumps of up to 128 other bytes (count, bytes).
Bytes encodeChannel(const Bytes& values) {
    constexpr size_t MIN_RUN = 4, MAX_RUN = 127, MAX_DUMP = 128;
    Bytes out;
    size_t i = 0;
    while (i < values.size()) {
        const size_t run = runLength(values, i, MAX_RUN);
        if (run >= MIN_RUN) {
            out.push_back(static_cast<unsigned char>(128 + run));
            out.push_back(values[i]);
            i += run;
            continue;
        }
        size_t end = i + 1;
        while (end < values.size() && end - i < MAX_DUMP && runLength(values, end, MIN_RUN) < MIN_RUN) ++end;
        out.push_back(static_cast<unsigned char>(end - i));
        out.insert(out.end(), values.begin() + static_cast<long>(i), values.begin() + static_cast<long>(end));
        i = end;
    }
    return out;
}

// A run-length encoded file: each scanline is 2, 2, width (big endian), then the four channels.
Bytes encodedHdr(int width, int height, const std::function<Bytes(int, int)>& pixel) {
    Bytes bytes = hdrHeader(width, height);
    for (int y = 0; y < height; ++y) {
        bytes.insert(bytes.end(), {2, 2, static_cast<unsigned char>(width >> 8), static_cast<unsigned char>(width & 255)});
        for (int channel = 0; channel < 4; ++channel) {
            Bytes values;
            for (int x = 0; x < width; ++x) values.push_back(pixel(x, y)[channel]);
            append(bytes, encodeChannel(values));
        }
    }
    return bytes;
}

// Pixels whose red channel needs both a dump (the first 10 columns differ) and a run.
Bytes dumpAndRunPixel(int x, int y) {
    return {static_cast<unsigned char>(x < 10 ? 10 + x : 50), static_cast<unsigned char>(20 + y), 30, 129};
}

Bytes uniformPixel(int, int) {
    return {64, 64, 64, 129};
}

// Signature and IHDR chunk of a PNG (8-bit grey): stb_image rejects the size before reading more.
Bytes pngHeaderOnly(uint32_t width, uint32_t height) {
    Bytes bytes = {0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 'I', 'H', 'D', 'R'};
    for (uint32_t value : {width, height})
        for (int shift = 24; shift >= 0; shift -= 8) bytes.push_back(static_cast<unsigned char>(value >> shift));
    append(bytes, {8, 0, 0, 0, 0, 0, 0, 0, 0});  // depth, grey, compression, filter, interlace, CRC
    return bytes;
}

std::string pngLoadFailure(const Bytes& png) {
    int w = 0, h = 0, c = 0;
    unsigned char* pixels = stbi_load_from_memory(png.data(), static_cast<int>(png.size()), &w, &h, &c, 4);
    if (pixels) {
        stbi_image_free(pixels);
        return "";
    }
    return stbi_failure_reason();
}

}  // namespace

class HdrLoaderTest : public ::testing::Test {
protected:
    void SetUp() override {
        // mkdtemp creates a fresh directory with mode 0700 under a unique name, the safe way to
        // use a shared temp directory (Sonar cpp:S5443 flags the temp path itself).
        std::string pattern = (fs::temp_directory_path() / "hdr_loader_test_XXXXXX").string();  // NOSONAR
        ASSERT_NE(mkdtemp(pattern.data()), nullptr);
        dir = pattern;
    }

    // Removes only the directory this test created, with the files it wrote.
    void TearDown() override { fs::remove_all(dir); }

    fs::path write(const std::string& name, const Bytes& bytes) {
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

    // The file whole must load; the file without its last byte must not.
    void expectLoadsWholeAndRejectsWithoutLastByte(const std::string& name, Bytes bytes) {
        const auto whole = load(write(name + ".hdr", bytes));
        EXPECT_TRUE(whole.has_value()) << name << " whole: " << error;
        bytes.pop_back();
        expectRejected(write(name + "_cut.hdr", bytes));
    }

    fs::path dir;
    std::string error;
};

TEST_F(HdrLoaderTest, ValidFileLoadsWithSizeAndPixelValue) {
    const auto image = load(write("valid.hdr", validHdr(64, 32)));
    ASSERT_TRUE(image.has_value()) << error;
    EXPECT_EQ(image->width, 64);
    EXPECT_EQ(image->height, 32);
    EXPECT_EQ(image->pixelCount(), 64u * 32u);
    // Pixel (5, 7) has bytes 15, 27, 30, 129; stb decodes a mantissa byte m as (m + 0.5) / 128.
    const float* pixel = image->rgba.get() + (7 * 64 + 5) * 4;
    EXPECT_FLOAT_EQ(pixel[0], 15.5f / 128.0f);
    EXPECT_FLOAT_EQ(pixel[1], 27.5f / 128.0f);
    EXPECT_FLOAT_EQ(pixel[2], 30.5f / 128.0f);
    EXPECT_FLOAT_EQ(pixel[3], 1.0f);
}

TEST_F(HdrLoaderTest, ValidFilesOfEveryAlignmentToTheReadBufferLoad) {
    // stb reads in 128-byte chunks: a complete file must never be flagged as truncated, whatever
    // its length modulo the chunk size.
    for (int width = 1; width <= 40; ++width) {
        const auto image = load(write("w" + std::to_string(width) + ".hdr", validHdr(width, 1)));
        EXPECT_TRUE(image.has_value()) << "width " << width << ": " << error;
    }
}

TEST_F(HdrLoaderTest, FlatFileEndingExactlyOnAReadChunkLoadsAndIsRejectedWithoutItsLastByte) {
    expectLoadsWholeAndRejectsWithoutLastByte("flat128", flatHdrOfSize(4, 1, 128));
    expectLoadsWholeAndRejectsWithoutLastByte("flat256", flatHdrOfSize(4, 1, 256));
}

TEST_F(HdrLoaderTest, RunLengthEncodedFileLoadsAndIsRejectedWithoutItsLastByte) {
    const Bytes encoded = encodedHdr(64, 8, dumpAndRunPixel);
    const auto image = load(write("rle.hdr", encoded));
    ASSERT_TRUE(image.has_value()) << error;
    EXPECT_EQ(image->width, 64);
    EXPECT_EQ(image->height, 8);
    const float* dumped = image->rgba.get() + (3 * 64 + 2) * 4;  // pixel (2, 3): red 12, green 23
    EXPECT_FLOAT_EQ(dumped[0], 12.5f / 128.0f);
    EXPECT_FLOAT_EQ(dumped[1], 23.5f / 128.0f);
    const float* run = image->rgba.get() + (3 * 64 + 40) * 4;  // pixel (40, 3): red 50, in the run
    EXPECT_FLOAT_EQ(run[0], 50.5f / 128.0f);

    Bytes cut = encoded;
    cut.pop_back();
    expectRejected(write("rle_cut.hdr", cut));
}

TEST_F(HdrLoaderTest, SmallestPossibleRunLengthEncodingStillLoads) {
    const Bytes encoded = encodedHdr(HDR_MAX_DIMENSION, 1, uniformPixel);
    EXPECT_EQ(static_cast<long long>(encoded.size() - hdrHeader(HDR_MAX_DIMENSION, 1).size()),
              minimumHdrDataBytes(HDR_MAX_DIMENSION, 1));
    const auto image = load(write("smallest.hdr", encoded));
    EXPECT_TRUE(image.has_value()) << error;
}

TEST_F(HdrLoaderTest, MinimumDataSizeFollowsTheEncodings) {
    EXPECT_EQ(minimumHdrDataBytes(4, 2), 4 * 4 * 2);           // too narrow for run-length encoding
    EXPECT_EQ(minimumHdrDataBytes(64, 8), (4 + 8 * 1) * 8);    // one run per channel
    EXPECT_EQ(minimumHdrDataBytes(128, 1), 4 + 8 * 2);         // 128 pixels need two runs of 127
    EXPECT_EQ(minimumHdrDataBytes(7, 1), 4 * 7);               // one pixel short of run-length encoding
    EXPECT_EQ(minimumHdrDataBytes(8, 1), 4 + 8 * 1);           // 8 pixels as four 2-byte runs
}

TEST_F(HdrLoaderTest, TrailingBytesAfterThePixelsAreIgnored) {
    Bytes bytes = validHdr(64, 32);
    bytes.insert(bytes.end(), 500, 'z');
    const auto image = load(write("trailing.hdr", bytes));
    EXPECT_TRUE(image.has_value()) << error;
}

TEST_F(HdrLoaderTest, HeaderCutAtAnyPointIsRejected) {
    const Bytes whole = hdrHeader(64, 32);
    for (size_t cut : {size_t{1}, size_t{5}, size_t{11}, size_t{20}, size_t{34}, whole.size() - 1, whole.size()}) {
        expectRejected(write("cut" + std::to_string(cut) + ".hdr", Bytes(whole.begin(), whole.begin() + static_cast<long>(cut))));
    }
}

TEST_F(HdrLoaderTest, FileCutToHalfItsLengthIsRejected) {
    Bytes bytes = validHdr(64, 32);
    bytes.resize(bytes.size() / 2);
    expectRejected(write("half.hdr", bytes));
}

TEST_F(HdrLoaderTest, FileCutByOneByteIsRejected) {
    Bytes bytes = validHdr(64, 32);
    bytes.pop_back();
    expectRejected(write("minus_one.hdr", bytes));
}

TEST_F(HdrLoaderTest, HeaderOnlyFileClaimingMaximumSizeIsRejectedBeforeAnyDecoding) {
    const fs::path path = write("header_only.hdr", hdrHeader(HDR_MAX_DIMENSION, HDR_MAX_DIMENSION));
    const auto start = std::chrono::steady_clock::now();
    expectRejected(path);
    const auto elapsed = std::chrono::steady_clock::now() - start;
    EXPECT_NE(error.find("too small"), std::string::npos) << error;
    EXPECT_NE(error.find("8192x8192"), std::string::npos) << error;
    if (TIMING_IS_MEANINGFUL) {
        EXPECT_LT(elapsed, std::chrono::milliseconds(MAX_REJECT_MILLISECONDS))
            << "stb_image decoded 1 GiB before noticing there is no data";
    }
}

TEST_F(HdrLoaderTest, HeaderClaimingHugeSizeIsRejectedWithTheSizeInTheMessage) {
    expectRejected(write("huge.hdr", hdrHeader(100000, 100000)));
    EXPECT_NE(error.find("too large"), std::string::npos) << error;
}

TEST_F(HdrLoaderTest, OneTooManyPixelsWideIsRejected) {
    expectRejected(write("too_wide.hdr", hdrHeader(HDR_MAX_DIMENSION + 1, 1)));
    EXPECT_EQ(error, "image too large: 8193x1, at most 8192");
}

TEST_F(HdrLoaderTest, OneTooManyPixelsHighIsRejected) {
    expectRejected(write("too_high.hdr", hdrHeader(1, HDR_MAX_DIMENSION + 1)));
    EXPECT_EQ(error, "image too large: 1x8193, at most 8192");
}

TEST_F(HdrLoaderTest, ExactlyMaximumWidthWithItsDataLoads) {
    const auto image = load(write("max_width.hdr", validHdr(HDR_MAX_DIMENSION, 1)));
    ASSERT_TRUE(image.has_value()) << error;
    EXPECT_EQ(image->width, HDR_MAX_DIMENSION);
    EXPECT_EQ(image->height, 1);
}

TEST_F(HdrLoaderTest, EmptyFileIsRejected) {
    expectRejected(write("empty.hdr", {}));
}

TEST_F(HdrLoaderTest, RandomBytesAreRejected) {
    // Seeded junk input for the parser, not a secret: a fixed, reproducible generator is the point.
    std::mt19937 random(12345);  // NOSONAR (cpp:S2245)
    Bytes bytes(1024);
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

TEST(StbImageCapTest, PngWiderThanTheCapIsRejectedAsTooLarge) {
    EXPECT_NE(pngLoadFailure(pngHeaderOnly(HDR_MAX_DIMENSION + 1, 1)).find("large"), std::string::npos);
}

TEST(StbImageCapTest, PngAtTheCapIsNotRejectedForItsSize) {
    // The same header at the cap fails later (it has no pixel data), but not for its size: this
    // is what makes the test above prove the cap and not a malformed header.
    const std::string reason = pngLoadFailure(pngHeaderOnly(HDR_MAX_DIMENSION, 1));
    EXPECT_FALSE(reason.empty());
    EXPECT_EQ(reason.find("large"), std::string::npos) << reason;
}
