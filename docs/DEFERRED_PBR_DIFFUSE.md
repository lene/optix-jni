# Deferred: F-PBR-DIFFUSE (physically based Lambertian diffuse)

**Status:** implemented, validated, **held back** — not in any release.
**Target sprint:** Sprint 35 (Visual Quality).
**Code:** commit `72843a0e2c6b070841234b5bbd29d186a9cb7e99`
(`feat(F-PBR-DIFFUSE): physically based Lambertian diffuse lighting`).
Preserved on branch `feature/sprint-33-caustics-multitarget` (pushed to origin) and
in the local merge branch `feature/sprint-33-caustics-integrate`. **Do not delete
those branches** until this work lands.

This document exists so the completed work does not have to be reproduced.

## What it does

Replaces the non-physical diffuse model in `calculateLighting`
(`src/main/native/shaders/helpers.cu`):

```
old:  0.3 * ambient  +  0.7 * (I / (1 + d²)) * N·L
new:  albedo/π * irradiance,  irradiance = Σ_i  intensity_i / d²_i * (N·L_i) * shadow_i  + IBL
```

- Removes the constant `AMBIENT_LIGHT_FACTOR = 0.3` fill.
- True inverse-square falloff `1/d²` (guarded by `MIN_ATTENUATION_DIST_SQ`) for point and
  area lights. Directional lights keep `attenuation = 1.0` (correct — infinitely far).
- Returns reflected radiance `total_lighting * INV_PI`; caller applies surface albedo.

Files touched: `src/main/native/shaders/helpers.cu` (the `calculateLighting` diffuse term
+ point/area attenuation guards; `getDirectionalLightParams` unchanged).

## Validation already done (do not repeat)

- pbrt-v4 canonical scene, whole-image floor-OFF **MSE 0.192 → 0.013**; mean brightness
  delta 28% → 10%. Residual is the glass sphere's refraction (a separate BRDF).
- Root-caused the resulting unit-test failures (2026-07-10): the math is **correct**, not
  buggy. Directional light `attenuation = 1.0`; at the tests' `intensity = 1.0` the peak
  reflected value is `albedo/π ≈ 0.318` (measured 81/255 on the lit cap — matches exactly).
  Surfaces facing away from the single light go pure black (no ambient fill).

## Why it is held back

The change is physically correct but **incomplete** — the commit itself deferred the
downstream work ("changes the shading of EVERY scene, all reference images regenerate…
a separate, deliberate step"), which was never done:

1. **~44 GPU tests fail** (`RendererSuite`, `MaterialSuite`, `CameraSuite`,
   `AreaLightSuite`, `RefractionSuite`, …). They encode the old ambient/brightness model:
   a colored sphere under one unit-intensity directional light now averages to near-black,
   so the center-50% dominant-channel check reads "gray". These are **real** regressions of
   the test expectations, not flaky.
2. **Default scenes render too dark for a showcase renderer.** Peak `albedo/π ≈ 0.32` with a
   black shadow side is physically defensible but visually poor at the default light level.

## Remaining work to complete (Sprint 35)

1. Decide the intended default look — pick one:
   - bump the default directional-light intensity (e.g. to `π`) so a fully-lit Lambertian
     surface returns ~`albedo`, and/or
   - add a small environment/IBL/ambient term so shadow sides are not pure black.
2. Regenerate all downstream reference images (optix-jni + menger integration suite).
3. Update the GPU-test expectations/scenes to the new shading (fill light or revised
   thresholds), per whichever look is chosen in (1).
4. Re-baseline the pbrt validation harness against the finalized light model.
5. Release as its own optix-jni version; bump menger's pin.

## How to resume

```
git checkout feature/sprint-33-caustics-multitarget   # has 72843a0 (PBR) on top of 0.1.13
# or cherry-pick 72843a0 onto the then-current main
git show 72843a0 -- src/main/native/shaders/helpers.cu
```
