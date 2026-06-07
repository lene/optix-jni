# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.1.2] - 2026-06-07

### Changed

- Bump `menger-common` dependency from `0.1.0` to `0.1.1` (removes dead `gpuProject4D` field)

## [0.1.1] - 2026-06-06

### Fixed

- Publish CI job now sets CUDA PATH before cmake (was building stub instead of real library)
- Build aborts if PTX not found after nativeCompile (prevents accidental stub publish)
- Stub CMake target renamed to match real target name (fixes liboptix_jni.so vs liboptixjni.so mismatch)

**Note:** `0.1.0` on Maven Central is defective (contains stub library). Use `0.1.1` or later.

## [0.1.0] - 2026-06-04

### Added

- Initial public release as standalone GPU ray tracing library (Sprint 25/26)
- Zero Menger-specific types — general-purpose OptiX JNI bindings

[0.1.2]: https://github.com/lene/optix-jni/compare/0.1.1...0.1.2
[0.1.1]: https://github.com/lene/optix-jni/compare/0.1.0...0.1.1
[0.1.0]: https://github.com/lene/optix-jni/releases/tag/0.1.0
