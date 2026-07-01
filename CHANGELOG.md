# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.1.8] - 2026-07-01

### Fixed

- `heroWavelengthToRGB` was defined but never called — spectral tint was dead code.
  Dispersion now correctly tints refracted rays by CIE wavelength response after
  recursive trace (Sprint 32.5 fix).

## [0.1.7] - 2026-07-01

### Added

- Cauchy dispersion parameters (`cauchy_a`, `cauchy_b`) on all `add*Instance` methods
- Shader support for hero-wavelength spectral sampling in `helpers.cu`
- `cauchyCoefficients()` helper from `menger-common` 0.1.4 (Sprint 32)

### Changed

- `menger-common` dependency bumped 0.1.2 → 0.1.4

## [0.1.6] - 2026-06-28

### Added

- OptiX validation mode via `MENGER_OPTIX_VALIDATION=1` (Sprint 30)
- Shader execution reordering (SER) via `MENGER_OPTIX_SER=1`, Ada+ GPU gated
- MiMa binary compatibility enforcement (baseline 0.1.5)
- Scaladoc CI gate extended to all public API files
- API review document (`docs/api-review-1.0.md`)
- 1.0 release checklist (`docs/release-checklist-1.0.md`)

### Changed

- `setDenoisingEnabled` and `isDenoisingEnabled` now guarded with `isInitialized`
  check — silent no-op instead of native SIGSEGV when called before init
- `setAccumulationFrames` guarded in `OptiXRenderApi`
- 8 legacy single-object methods deprecated with migration paths to IAS API:
  `setIOR`, `setScale`, `setSphereColor`, `setTriangleMeshIOR`,
  `setTriangleMeshColor`, `clearTriangleMesh`, `hasTriangleMesh`,
  `setLight(direction, intensity)`
- GPU stream tunable removed — `optixLaunch` requires `CUstream` pointer,
  not unsigned int

### Fixed

- Per-frame GPU buffer re-allocation: 8 geometry-data arrays in IAS render path
  now only re-allocate on actual size changes (was unconditional)

## [0.1.5] - 2026-06-25

### Added

- Opt-in OptiX HDR denoiser: `OptiXDenoiser` Scala wrapper, `NativeOptiXApi`
  lifecycle methods (`createDenoiser`, `denoiseFloat4`, `destroyDenoiser`), and
  `OptiXRenderer.setDenoisingEnabled`. Disabled by default; existing render output
  unchanged when off. Guide AOVs (albedo, normal) supported via `DenoiseGuides`.
- World-space round cubic B-spline curves: `OptiXRenderer.addCurveInstance(points,
  widths, material)` backed by OptiX built-in curve GAS and
  `NativeOptiXApi.createCurveHitGroup`. Requires ≥ 4 control points; widths are
  per-control-point radii.

## [0.1.4] - 2026-06-14

### Changed

- Pin the CUDA Toolkit to the 13.x major version (`find_package(CUDAToolkit 13.0)`), failing
  the build on any other major. Published artifacts link the CUDA runtime
  (`libcudart.so.<major>`); the previous unpinned `>=12.0` let the build host's toolkit
  silently set the runtime ABI and minimum driver. 0.1.3 incidentally linked
  `libcudart.so.13`, raising the consumer's minimum NVIDIA driver to ≥580.65 with no record.
  Pinning makes the runtime ABI and driver floor deliberate and reproducible.

## [0.1.3] - 2026-06-11

### Added

- Add `OptiXRenderer.updateTexture` for in-place RGBA8 texture slot updates.

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

[0.1.9]: https://github.com/lene/optix-jni/compare/0.1.8...0.1.9
[0.1.8]: https://github.com/lene/optix-jni/compare/0.1.7...0.1.8
[0.1.7]: https://github.com/lene/optix-jni/compare/0.1.6...0.1.7
[0.1.6]: https://github.com/lene/optix-jni/compare/0.1.5...0.1.6
[0.1.5]: https://github.com/lene/optix-jni/compare/0.1.4...0.1.5
[0.1.4]: https://github.com/lene/optix-jni/compare/0.1.3...0.1.4
[0.1.3]: https://github.com/lene/optix-jni/compare/0.1.2...0.1.3
[0.1.2]: https://github.com/lene/optix-jni/compare/0.1.1...0.1.2
[0.1.1]: https://github.com/lene/optix-jni/compare/0.1.0...0.1.1
[0.1.0]: https://github.com/lene/optix-jni/releases/tag/0.1.0

## [0.1.9] - 2026-07-01

### Fixed

- `addPlaneInstanceNative` was missing `cauchy_a/cauchy_b` parameters in the
  JNI binding, Scala @native declaration, and wrapper call. Parameters were
  shifted, corrupting checkerboard planes (solid-color instead of checker).
  (Sprint 32 regression caught by `plane IS checker` integration test)

