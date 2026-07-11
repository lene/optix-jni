# Production-Level Caustics — Roadmap + Phase 1 (Coverage Net)

**Date:** 2026-07-11
**Status:** Phase 1 approved for implementation; Phases 2–4 sequenced, specs to follow.
**Repos:** optix-jni (caustics engine), menger (consumption + integration/manual tests).

## Context — what already ships (optix-jni 0.1.12–0.1.16)

Progressive photon mapping (PPM) with multi-iteration radius shrink · point / directional /
area lights → caustics · multi-object per-instance photon targeting · reflective **and**
refractive transport (exact-Fresnel Russian roulette) · Beer-Lambert absorption (tinted glass) ·
dispersion (per-photon hero wavelength, Cauchy `n(λ)=a+b/λ²`, CIE tint) · energy-correct emission
measure · Lambertian floor deposit · opt-in OptiX HDR denoiser.

`CausticsValidationSuite` (C1–C5) covers the physics **analytically**: emission, geometric hit
rate, Snell refraction, TIR, focal-point position, Fresnel reflectance, Beer-Lambert absorption —
all as *calculations*. `MultiObjectCausticsSuite` and `AreaLightCausticsSuite` lock the 0.1.16
emitter behaviors end-to-end.

## Roadmap — 4 phases (approved sequencing)

Risk-ascending on the base, with the temporal layer last so it wraps a settled pipeline.

1. **Phase 1 — Coverage net** *(this spec)*. Tests only, zero production-code change. Lock four
   shipped-but-unasserted **end-to-end deposit** behaviors before later phases perturb the
   emitter/deposit path.
2. **Phase 2 — Arbitrary receiver surfaces.** Photon deposit moves from floor-plane/grid to any
   diffuse surface. Biggest realism ceiling; unblocks water (future) and volumetric (future).
3. **Phase 3 — Rough / frosted refraction (GGX BTDF).** Self-contained shader change; soft
   caustics from microfacet-rough dielectrics. Independent of Phase 2.
4. **Phase 4 — Temporal stability for animation.** Kill caustic flicker across video frames
   (seed reuse / temporal accumulation / denoiser temporal mode). Last, so it layers on a stable
   emitter+deposit.

Each phase gets its own spec → plan → implementation cycle. Phases 2–4 are not detailed here.

## Hard requirement (all phases, non-negotiable)

Repo policy: **every rendering feature ships with both** a headless integration-suite regression
(`scripts/integration-tests.sh`) **and** a manual interactive scene (`scripts/manual-test.sh`).
No phase is done until both exist and are verified. Reference-image diffs are resolved in the same
commit that introduces the rendering change.

## Phase 1 — detailed design

**Goal:** characterize and lock four behaviors that are implemented but have no end-to-end
assertion. The *calculation* is tested today; the *resulting caustic deposit* is not.

### A. optix-jni unit suites

New assertions via `RendererFixture` + `CausticsStats` + rendered-region inspection, GPU-gated
(`assume`) and sanitizer-cancelled exactly like the existing caustics suites.

1. **Tinted-glass caustic color.** Red-tinted glass (`Color(1.0, 0.2, 0.2, α)`) with caustics on →
   mean RGB over the caustic floor region has R clearly dominating G and B. Asserts Beer-Lambert
   tints *the deposited caustic*, not merely the absorption calculation (C5).
2. **Reflective caustic presence.** A geometry/light configuration that produces a reflective
   caustic → the reflected-photon deposit is non-zero (`CausticsStats` reflect/deposit counters,
   and/or a caustic region that vanishes when reflection is suppressed). Locks the P2 reflect path.
3. **PPM dispersive caustic deposit.** A dispersive material with caustics on → the deposited
   caustic region shows chromatic spread (channel-separated tint) versus the same scene with
   dispersion off. Locks the 0.1.13 photon-mapped spectral floor caustic (distinct from menger's
   primary-ray `test_spectral_dispersion`).
4. **Energy conservation guard.** Full caustics render → `CausticsStats.energyConservationError`
   below a tolerance (emitted ≈ deposited + absorbed + reflected). One cheap regression catching
   emission/deposit accounting drift.

**Discriminators are stats/region-based, not exact-pixel**, to stay robust to GPU nondeterminism
in the raw buffer while still asserting the qualitative behavior.

### B. menger integration + manual (repo policy)

- `test_colored_glass_caustics` (integration) + a manual colored-glass caustic scene — red/colored
  glass casting a tinted floor caustic.
- `test_reflective_caustics` (integration) + a manual reflective-caustic scene.
- Dispersion already has `test_spectral_dispersion` (integration); **add a manual PPM-dispersive
  caustic scene** if none exists, for visual parity.

New integration references generated with `--update-references`, then re-verified for determinism
(run-to-run identical) before commit, following the Phase 0 discipline.

### C. Validation approach

- Each unit assertion is written first and run against current code.
  - Passes → characterization test locking present behavior. Keep.
  - **Fails → a real bug is exposed.** Stop, apply systematic-debugging, fix root cause, then the
    test guards the fix. (This is the point of a coverage net: it may find that a "shipped"
    behavior is subtly wrong.)
- optix-jni gate (`.git_hooks/pre-push`) green before push; menger DoD gate green before push.

## Out of scope (Phase 1)

No changes to the emitter, deposit path, or any shader. No new caustics *features* — area-light
and multi-object are already locked by their own suites. Water, volumetric, metallic/conductor,
full-spectral, SPPM, and view-importance emission are future Tier-2/3 items, not scheduled here.

## Success criteria

- Four new optix-jni unit assertions green (or a bug found, fixed, and guarded).
- `test_colored_glass_caustics` + `test_reflective_caustics` in the integration suite with
  deterministic references; manual scenes for colored, reflective, and PPM-dispersive caustics.
- Both gates green. No rendering-behavior change to existing scenes (this is tests-only, so
  existing reference images must be **unchanged**).
