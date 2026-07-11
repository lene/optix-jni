# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.1.16] - 2026-07-11

Soft caustics from area lights (Sprint 33.11 / F-CAUSTICS-AREA).

### Added

- **Area-light photon emission** (`emitAreaPhoton`): an `AREA` light now emits caustic photons from
  uniformly sampled points on its emitter disk instead of a single origin. Spreading the origins
  over the disk blurs the caustic into a soft, lower-peak penumbra — a point light's single origin
  gives a sharp caustic. Reuses the per-instance solid-angle cone aiming shared with the point light
  (extracted into `aimPhotonFromOrigin`), so multi-object targeting works for area lights too. Area
  lights were already plumbed end-to-end for soft shadows; this closes the caustics path. Before
  this, an `AREA` light fell through to the point-light emitter and produced a hard caustic.
- `AreaLightCausticsSuite` — regression test: at matched position/intensity/target an area light's
  peak caustic brightness (`CausticsStats.maxCausticBrightness`) must sit below the point light's,
  i.e. the caustic is measurably softer.

### Changed

- **Refactor (no behaviour change for point/directional):** the point light's per-instance target
  selection + cone sampling moved into `aimPhotonFromOrigin`, called by both `emitPointPhoton` and
  `emitAreaPhoton`. The `rnd()` draw order is preserved, so point and directional caustics are
  bit-identical to 0.1.15.

## [0.1.15] - 2026-07-11

Per-instance photon emission for correct multi-object caustics (Sprint 33.11 / F-CAUSTICS-MULTITARGET).

### Changed

- **Multi-object photon emission** (both point and directional lights): photons now aim at one
  bounding target **per refractive instance** instead of a single merged sphere spanning all of
  them. The old merged target sat in the empty gap between separated objects, so most photons flew
  through the middle and never refracted, yielding weak, mispositioned caustics for N > 1 objects.
  `emitPointPhoton` picks a target with probability ΔΩ_i / ΣΔΩ (flux Φ = I·ΣΔΩ/N);
  `emitDirectionalPhoton` picks a target's emission disk with probability A_i / ΣA (flux
  Φ = E·ΣA/N). Overlapping cones/disks double-count the overlap (documented approximation,
  negligible for separated objects). A single refractive object is **bit-identical** to the
  pre-multitarget emission (the CDF draw is guarded on N > 1, so no RNG-stream shift).
- **Auto gather radius now scales with per-instance object size** (mean of the per-instance
  target radii) instead of the merged bounding radius. For several separated objects the merged
  span is far larger than any one object, so the old auto radius over-smoothed each caustic into
  a blur; the per-instance mean keeps multi-object caustics as sharp as the single-object case.
- **`CAUSTICS_AUTO_RADIUS_FACTOR` recalibrated 0.6 -> 0.1.** The 0.6 was tuned against a
  measurement-buggy caustic-delta metric (a vertically-flipped mask + far-floor contamination
  overstated agreement). Against the corrected region-isolated metric, 0.6 over-smoothed the
  caustic (region correlation vs pbrt ~0.59); ~0.1*object_radius maximises the match (~0.73, the
  primary-ray structural ceiling) without the gather going noisy from low photon density.

### Added

- `CausticsParams.caustic_targets[MAX_CAUSTIC_TARGETS*4]` + `num_caustic_targets` — per-instance
  emission target list (packed center xyz + radius). `MAX_CAUSTIC_TARGETS = 16`; scenes with
  more refractive instances fall back to the merged single target. The merged
  `caustic_target_center/radius` remain for grid bounds + auto radius.
- `MultiObjectCausticsSuite` — regression test locking in the fix for **both** light types. Two
  separated glass spheres must retain the single-sphere photon-refraction rate (~1×); the pre-fix
  merged target collapses it (point light to < 10%, directional to ~13%). Asserts on
  `CausticsStats.refractionEvents` (immune to the fixed-photon-budget confound where total
  deposited energy stays ~constant while peaks halve).

## [0.1.14] - 2026-07-06

Shadow rays enabled by default + Fresnel-based dielectric shadow attenuation (Sprint 33).

### Changed

- **Shadows default to ON**: `shadows_enabled` now defaults to `true` instead of `false`.
- **Fresnel-based dielectric shadows**: Glass and other dielectrics (IOR > 1.0) now compute
  shadow opacity from the Fresnel normal-incidence reflectance instead of the near-zero
  surface alpha. Glass (IOR 1.5) blocks ~8% of direct light vs the old ~2%, producing
  a physically-motivated visible shadow.

## [0.1.13] - 2026-07-04

Caustics auto-tuning + dispersive photon caustics (Sprint 33.8 / 33.10).

### Added

- **Geometry-scaled auto gather radius**: when the caller leaves the PPM gather radius unset
  (`initial_radius <= 0`), it is derived from the refractive geometry's bounding radius
  (`CAUSTICS_AUTO_RADIUS_FACTOR * caustic_target_radius`) so bare caustics scale with object
  size instead of a fixed 1.0 world-space radius. Dormant when an explicit radius is passed.
- **`MENGER_CAUSTICS_RADIUS`** environment override — a runtime gather-radius calibration knob
  used to sweep the factor against the pbrt caustic-delta harness. Unset in normal use.
- **Dispersive photon caustics**: the PPM photon path carries a per-photon hero wavelength
  (payload p10), refracts with the Cauchy `n(λ) = a + b/λ²` for dispersive instances, and
  tints the deposited flux by the wavelength's CIE response — spectral (rainbow) floor
  caustics. Non-dispersive scenes are bit-identical (wavelength unread, flux untinted).

### Notes

- Calibration finding: menger renders primary-ray caustics only (no SDS paths), so the caustic
  carries ~1/5 the energy of a full pbrt SPPM render at every gather radius. Spatial
  correlation (caustic shape, ~0.86 vs pbrt) — not energy ratio — is the achievable acceptance
  criterion.

## [0.1.12] - 2026-07-03

Physics rebuild of the progressive-photon-mapping caustics pipeline (Sprint 33).
Fixes nine structural defects that made caustic brightness/shape uncalibratable;
validated against pbrt-v4 (`sppm`) via a caustic-delta metric — spatial
correlation with the reference rose from 0.11 (broken) to 0.86 (> 0.8 target).

### Fixed

- **P1 — emission measure**: photon flux now carries the cone/disk emission
  measure (point `I·2π(1−cosθmax)/N`, directional `E·π·r²/N`) instead of the
  bare `intensity/N`. Root cause of the long-running scale-factor chasing.
- **P2 — Fresnel-reflected energy discarded**: photons now Russian-roulette
  between reflect and refract with probability F (flux unweighted) instead of
  always refracting weighted `(1−F)`. Enables reflective caustics.
- **P3 — exact dielectric Fresnel** replacing the Schlick approximation.
- **P4 — non-physical composite**: caustic radiance is added linearly and passes
  through the single global tone-map operator instead of a private exponential
  tone map + screen blend into the 8-bit buffer.
- **P5 — density estimate**: uniform-disk deposit (dropped the spurious cosθ
  weight and unnormalized Gaussian) with Lambertian floor-albedo ρ/π.
- **P6 — cross-iteration normalization**: radiance divides accumulated flux by
  the iteration count (brightness was scaling ~linearly with iterations).
- **P8 — grid bounds**: photon-deposition grid derived from the refractive
  geometry's bounding sphere instead of a hard-coded ±3 box.
- **P9 — direct-light double counting**: only LS⁺D paths (photons that touched a
  specular surface) are deposited.
- `CausticsStats` JNI `FindClass` corrected to the top-level
  `io/github/lene/optix/CausticsStats` (was a stale nested-class name).

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

[0.1.16]: https://github.com/lene/optix-jni/compare/0.1.15...0.1.16
[0.1.15]: https://github.com/lene/optix-jni/compare/0.1.14...0.1.15
[0.1.14]: https://github.com/lene/optix-jni/compare/0.1.13...0.1.14
[0.1.13]: https://github.com/lene/optix-jni/compare/0.1.12...0.1.13
[0.1.12]: https://github.com/lene/optix-jni/compare/0.1.11...0.1.12
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


## [0.1.11] - 2026-07-02

### Added

- `metallic_texture_index`, `ao_texture_index`, `height_texture_index` to InstanceMaterial
- shader accessors: `getInstanceMetallicTextureIndex`, `getInstanceAoTextureIndex`,
  `getInstanceHeightTextureIndex`
- `applyMetallicMap(metallic, uv, index)` — per-texel metallic (multiplies scalar parameter)
- `applyAOMap(color, uv, index)` — per-texel ambient occlusion (multiplies diffuse term)
- Extended `setMapTextures` to accept metallic/ao/height texture indices

### Changed

- All hit shaders (hit_sphere.cu, hit_plane.cu, hit_triangle.cu) apply metallic + AO maps
  at closest-hit alongside existing normal/roughness maps
