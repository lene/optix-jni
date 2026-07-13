# Rough / Frosted Refraction (Phase 3) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Caustics from rough/frosted dielectrics (`roughness > 0`) spread and soften instead of staying pin-sharp — GGX-VNDF microfacet sampling perturbs the refraction (and reflection) normal in the photon path, gated on the already-live-but-caustics-unread `InstanceMaterial.roughness` field.

**Architecture:** `__closesthit__photon`'s existing Snell/Fresnel/Russian-roulette block (`caustics_ppm.cu:1071-1151`) already computes everything a rough BTDF needs (`n1`/`n2`/`eta`/`cos_theta_i`) from one oriented geometric normal. Phase 3 inserts one step before that block: when the hit instance's `roughness > 0`, importance-sample a microfacet normal `m` around the geometric normal via GGX-VNDF (Heitz 2018), then have the *existing* Fresnel/reflect/refract code use `m` instead of the smooth geometric normal for the rest of the calculation — no restructuring of the existing physics, one new `shading_normal` variable threaded through 3 existing lines. The two uniform draws GGX sampling needs are produced by the same ad-hoc-TEA-hash pattern the Russian-roulette split already uses for `rr_seed` (`:1139-1140`) — no payload-register plumbing, no access to the true photon RNG seed required.

**Tech Stack:** CUDA/OptiX (`.cu` shaders), Scala 3 ScalaTest (`AnyFlatSpec`), `RendererFixture`/`OptiXRenderer`/`CausticsStats`, menger-common (`Material`), bash integration/manual scripts.

## Global Constraints

- Scala 3 only; no `var`/`while`/`asInstanceOf`/`throw` in production (tests may use `@SuppressWarnings` + `scalafix:ok`, matching existing caustics suites). Max line length 100. No magic numbers — named constants.
- C++/CUDA: manual edits only — no `sed`/scripts for bulk edits (has corrupted code before). Match existing style in `caustics_ppm.cu` (Doxygen-style `/** */` headers on new `__device__` functions, dated rationale comments on non-obvious changes).
- GPU tests self-skip when no GPU (`assume(OptiXRenderer.isLibraryLoaded, ...)` via `RendererFixture`) and cancel under compute-sanitizer (`if runningUnderSanitizer then cancel(...)`).
- **RNG-regression discipline (non-negotiable):** the new GGX-sampling branch is gated behind `alpha > ROUGHNESS_EPSILON`, false for every existing reference scene — every dielectric preset (`Glass`, `Water`, `Diamond`, `GlassDispersive`, `DiamondDispersive`, `menger-common/.../Material.scala:58-92`) sets `roughness = 0.0f` explicitly. No existing reference image may drift; this mirrors the `roughness>0`/`ior<=1.05` guards from Phase 2.
- `Material` (menger-common): `Material(color: Color, ior: Float = 1.0f, roughness: Float = 0.0f, ...)` — note the *class* default is `0.0f`, distinct from `InstanceMaterial`'s struct doc-comment default of `0.5f` (that 0.5 is a C++-side fallback for callers that never set the field at all; every Scala `Material` construction always sets it explicitly).
- `InstanceMaterial.roughness` (`OptiXData.h:180`): `float roughness; // 0=mirror, 1=diffuse (default: 0.5)`. Read via `params.instance_materials[optixGetInstanceId()].roughness`, gated on `params.use_ias && params.instance_materials` (the legacy non-IAS single-object path has no roughness field at all — same gate the existing dispersion block at `:1058` already uses).
- `Const.iorGlass = 1.5f`, `Const.defaultFloorPlaneY`. `enableCaustics(photonsPerIter: Int = 100000, iterations: Int = 10, ...)`. `renderer.renderWithStats(imageSize).get: RenderResult`. `renderer.getCausticsStats: CausticsStats` — `energyConservationError` and `totalFluxReflected`/`totalFluxDeposited`/`totalFluxAbsorbed`/`totalFluxEmitted` are existing fields/derived methods (`OptiXRenderer.scala:110-152`), do not reimplement.
- Proven-visible caustic framing (mirror `CausticsCoverageSuite.setupGlassScene`): radius-1 glass sphere, floor at `Const.defaultFloorPlaneY`, **directional** light `(0,-1,0)` at **intensity 500** (dimmer quantizes to 0 in 8-bit), camera eye `(0,4,8)` look-at `(0,0,0)`, up `(0,-1,0)` (inverted), FOV 45.
- menger repo policy (hard requirement): every rendering feature ships a headless integration test **and** a manual scene; reference-image diffs resolved in the same commit; verify determinism (run-to-run identical) before committing references.
- Never commit to `main`; never push without explicit user confirmation; never `git add -A`; every commit trailer includes `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>` and `Claude-Session: https://claude.ai/code/session_0128FY5RY2p5AZorgHML8u6G`.

---

### Task 1: GGX-VNDF sampling primitives + roughness-gated microfacet refraction

**Repo:** optix-jni

**Files:**
- Modify: `src/main/native/shaders/caustics_ppm.cu` (`__closesthit__photon` at `:1071-1151`; new device functions near `sampleDisk` at `:66`)
- Modify: `src/main/native/include/OptiXData.h` (new `ROUGHNESS_EPSILON` constant near `FLUX_EPSILON` at `:93`)
- Create: `src/test/scala/io/github/lene/optix/caustics/CausticsRoughGlassSuite.scala`

**Interfaces:**
- Produces: `alphaFromRoughness(float roughness) -> float` (α = roughness²) and `sampleGGXVNDF(float alpha, const float3& v_local, float u1, float u2) -> float3` (Heitz 2018 isotropic VNDF sample, in the same local frame as `v_local`), both `__device__` functions in `caustics_ppm.cu`. `__closesthit__photon` gains a `shading_normal` local (== geometric `normal` when smooth, == sampled microfacet normal when `alpha > ROUGHNESS_EPSILON`) used in place of `normal` for `cos_theta_i`/`sin_theta_i_sq`/`sin_theta_t_sq`/`reflect_dir`/the refract formula.
- Consumes (existing, unchanged): `createONB(n, t, b)` (`:49`), `sampleDisk(u1, u2)` (`:66`, returns `float2` via concentric-disk mapping — reused directly as Heitz's `(t1, t2)` polar sample), `tea`/`rnd` (`:121-140`), `VectorMath.h` operators (`float3+float3`, `float3-float3`, `float*float3`/`float3*float`, `float3*float3` component-wise, `dot`, `cross`, `normalize` — all pre-existing, confirmed present).

- [ ] **Step 1: Write the failing test — rough glass caustic is measurably more spread than smooth**

Create `src/test/scala/io/github/lene/optix/caustics/CausticsRoughGlassSuite.scala`:

```scala
package io.github.lene.optix.caustics

import io.github.lene.optix.RendererFixture
import io.github.lene.optix.ImageValidation
import io.github.lene.optix.CausticsStats
import menger.common.Color
import menger.common.Const
import menger.common.ImageSize
import menger.common.Material
import menger.common.Vector
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Phase 3: GGX-VNDF rough refraction. roughness is read nowhere in the caustics path before
  * this phase, so a rough-glass caustic and a smooth-glass caustic are pixel-for-pixel identical
  * pre-implementation (same seeds, same physics, roughness silently ignored). Post-implementation,
  * the rough caustic must spread its energy over a wider area — measured as a LOWER standard
  * deviation of caustic-ROI pixel brightness than the smooth case (spread energy = flatter
  * distribution), not a raw pixel diff (CausticsWallReceiverSuite's own comment on ~10%
  * cross-scene composition noise applies here too; stddev of the SAME scene's own pixels sidesteps
  * that entirely, since both renders share identical geometry/camera/light).
  */
class CausticsRoughGlassSuite extends AnyFlatSpec with Matchers with RendererFixture:

  private val runningUnderSanitizer: Boolean =
    sys.env.get("RUNNING_UNDER_COMPUTE_SANITIZER").contains("true")

  private val imageSize: ImageSize = ImageSize(200, 150)
  private val sphereRadius = 1.0f
  private val photonsPerIter = 100000
  private val causticIterations = 4
  private val lightIntensity = 500.0f

  private val sphereTransform: Array[Float] =
    Array(sphereRadius, 0f, 0f, 0f, 0f, sphereRadius, 0f, 0f, 0f, 0f, sphereRadius, 0f)

  private def setupScene(material: Material): Unit =
    renderer.clearAllInstances()
    renderer.addSphereInstance(sphereTransform, material)
    renderer.clearPlanes()
    renderer.addPlane(1, positive = true, Const.defaultFloorPlaneY)
    renderer.setCamera(
      Vector[3](0.0f, 4.0f, 8.0f),
      Vector[3](0.0f, 0.0f, 0.0f),
      Vector[3](0.0f, -1.0f, 0.0f),
      45.0f
    )
    renderer.setLight(Vector[3](0.0f, -1.0f, 0.0f), lightIntensity)

  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  private def causticRoiStddev(material: Material): Double =
    setupScene(material)
    renderer.enableCaustics(photonsPerIter, causticIterations)
    val result = renderer.renderWithStats(imageSize).get
    // Floor spans the lower half of the frame at this camera framing (CausticsCoverageSuite's
    // proven-visible setup); restrict the stddev to that band so background/sphere pixels
    // (identical between the two renders regardless of roughness) don't dilute the signal.
    val roiPixels = for
      y <- imageSize.height / 2 until imageSize.height
      x <- 0 until imageSize.width
    yield ImageValidation.getRGBAt(result.image, imageSize, x, y)
    val brightness = roiPixels.map(p => (p.r.toDouble + p.g.toDouble + p.b.toDouble) / 3.0)
    val mean = brightness.sum / brightness.length
    math.sqrt(brightness.map(b => (b - mean) * (b - mean)).sum / brightness.length)

  behavior of "GGX-VNDF rough refraction"

  it should "spread a rough-glass caustic wider (lower ROI stddev) than a smooth-glass caustic" in:
    if runningUnderSanitizer then cancel("Skipped under compute-sanitizer (too slow)")
    val smoothGlass = Material(Color(0.95f, 0.95f, 1.0f, 0.5f), ior = Const.iorGlass, roughness = 0.0f)
    val roughGlass = Material(Color(0.95f, 0.95f, 1.0f, 0.5f), ior = Const.iorGlass, roughness = 0.4f)
    val smoothStddev = causticRoiStddev(smoothGlass)
    val roughStddev = causticRoiStddev(roughGlass)
    withClue(s"caustic ROI brightness stddev: smooth=$smoothStddev rough=$roughStddev; a rough " +
      "(frosted) glass caustic must spread its energy over a wider area than a smooth one, " +
      "measured as a LOWER stddev (flatter brightness distribution). ") {
      roughStddev should be < smoothStddev
    }

end CausticsRoughGlassSuite
```

- [ ] **Step 2: Run and confirm it fails against current code**

```bash
sbt "testOnly io.github.lene.optix.caustics.CausticsRoughGlassSuite"
```

Expected: FAIL — `roughStddev` should equal `smoothStddev` exactly (roughness is unread by the caustics path today, so both renders are physically identical modulo the RNG streams' incidental use of unrelated draws — they should still differ only by noise, not systematically, and definitely not with `rough < smooth`). If the failure looks like anything other than "no systematic difference," STOP and investigate before implementing.

- [ ] **Step 3: Implement — add `ROUGHNESS_EPSILON` to `OptiXData.h`**

In `src/main/native/include/OptiXData.h`, immediately after `FLUX_EPSILON` (`:93`):

```cpp
    constexpr float FLUX_EPSILON = 1e-10f;             // Near-zero flux/area threshold for caustics
    constexpr float ROUGHNESS_EPSILON = 1e-4f;         // Below this, treat as perfectly smooth (skip GGX sampling)
```

- [ ] **Step 4: Implement — add `alphaFromRoughness` and `sampleGGXVNDF` to `caustics_ppm.cu`**

Insert immediately after `sampleDisk` (`caustics_ppm.cu:66-70`), before `sampleSphere`:

```cpp
/**
 * Map perceptual roughness [0,1] to GGX alpha (Disney/UE4 remap: alpha = roughness^2).
 * Keeps roughness perceptually linear -- roughness=0.3 vs 0.5 are visibly, not marginally,
 * different, matching how `roughness` already reads for primary-ray PBR shading elsewhere
 * in this codebase (helpers.cu/hit_*.cu), even though those paths don't do GGX sampling.
 */
__device__ float alphaFromRoughness(float roughness) {
    return roughness * roughness;
}

/**
 * Sample a visible microfacet normal via GGX-VNDF importance sampling
 * (Heitz 2018, "Sampling the GGX Distribution of Visible Normals", isotropic case).
 *
 * @param alpha GGX roughness parameter (alphaFromRoughness(roughness))
 * @param v_local View direction in the local shading frame (z = macro-surface normal);
 *                must satisfy v_local.z >= 0 (caller ensures this by building the frame
 *                around a normal oriented toward the view direction).
 * @param u1 Random value [0, 1)
 * @param u2 Random value [0, 1)
 * @return Sampled microfacet normal, in the same local frame as v_local.
 */
__device__ float3 sampleGGXVNDF(float alpha, const float3& v_local, float u1, float u2) {
    // Section 3.2: stretch the view vector into the hemisphere configuration.
    const float3 vh = normalize(make_float3(alpha * v_local.x, alpha * v_local.y, v_local.z));

    // Section 4.1: orthonormal basis around vh.
    const float lensq = vh.x * vh.x + vh.y * vh.y;
    const float3 t1 = lensq > 0.0f
        ? make_float3(-vh.y, vh.x, 0.0f) * rsqrtf(lensq)
        : make_float3(1.0f, 0.0f, 0.0f);
    const float3 t2 = cross(vh, t1);

    // Section 4.2: parameterization of the projected area. sampleDisk already returns the
    // (r*cos(phi), r*sin(phi)) concentric-disk sample Heitz's algorithm needs as (t1, t2).
    const float2 disk = sampleDisk(u1, u2);
    const float s = 0.5f * (1.0f + vh.z);
    const float t2_warped = (1.0f - s) * sqrtf(fmaxf(0.0f, 1.0f - disk.x * disk.x)) + s * disk.y;

    // Section 4.3: reprojection onto the hemisphere.
    const float3 nh = disk.x * t1 + t2_warped * t2
        + sqrtf(fmaxf(0.0f, 1.0f - disk.x * disk.x - t2_warped * t2_warped)) * vh;

    // Section 3.4: transform the normal back to the ellipsoid configuration.
    return normalize(make_float3(alpha * nh.x, alpha * nh.y, fmaxf(0.0f, nh.z)));
}
```

- [ ] **Step 5: Implement — sample and use the microfacet normal in `__closesthit__photon`**

Replace the "Determine entering vs exiting" block (`caustics_ppm.cu:1071-1079`):

```cpp
    // Determine entering vs exiting
    const bool entering = dot(ray_dir, outward_normal) < 0.0f;
    const float3 normal = entering ? outward_normal : make_float3(-outward_normal.x, -outward_normal.y, -outward_normal.z);
    const float n1 = entering ? 1.0f : ior_material;
    const float n2 = entering ? ior_material : 1.0f;
    const float eta = n1 / n2;
    const float cos_theta_i = fabsf(dot(ray_dir, normal));
    const float sin_theta_i_sq = 1.0f - cos_theta_i * cos_theta_i;
    const float sin_theta_t_sq = eta * eta * sin_theta_i_sq;
```

with:

```cpp
    // Determine entering vs exiting
    const bool entering = dot(ray_dir, outward_normal) < 0.0f;
    const float3 normal = entering ? outward_normal : make_float3(-outward_normal.x, -outward_normal.y, -outward_normal.z);
    const float n1 = entering ? 1.0f : ior_material;
    const float n2 = entering ? ior_material : 1.0f;
    const float eta = n1 / n2;

    // Phase 3: GGX-VNDF rough refraction. Sample a microfacet normal around the macro `normal`
    // when the instance is rough, and use it in place of the smooth geometric normal for the
    // Fresnel/reflect/refract math below (both outcomes of the existing Russian-roulette split
    // read shading_normal, so rough glass blurs both its reflection and its transmission from one
    // sampled normal). roughness is read HERE for the first time in the caustics path -- gated on
    // alpha > ROUGHNESS_EPSILON, false for every existing dielectric preset (all set
    // roughness=0.0f explicitly), so this branch is unreachable for any current reference scene.
    float3 shading_normal = normal;
    if (params.use_ias && params.instance_materials) {
        const unsigned int rough_id = optixGetInstanceId();
        const float roughness = params.instance_materials[rough_id].roughness;
        const float alpha = alphaFromRoughness(roughness);
        if (alpha > ROUGHNESS_EPSILON) {
            float3 tangent, bitangent;
            createONB(normal, tangent, bitangent);
            const float3 view_world = make_float3(-ray_dir.x, -ray_dir.y, -ray_dir.z);
            const float3 view_local = make_float3(
                dot(view_world, tangent), dot(view_world, bitangent), dot(view_world, normal));
            // Fresh ad-hoc TEA-hash draws, same technique the Russian-roulette split below uses
            // for rr_seed (:1139-1140) -- different hit_point/flux component combinations, so this
            // draw doesn't correlate with the RR draw. No payload plumbing needed: the true photon
            // RNG seed is inaccessible here regardless, exactly as it is for rr_seed today.
            unsigned int ggx_seed1 = tea(__float_as_uint(hit_point.y + hit_point.z),
                                         __float_as_uint(flux.y + hit_point.x));
            unsigned int ggx_seed2 = tea(__float_as_uint(flux.z + hit_point.x),
                                         __float_as_uint(hit_point.y + flux.x));
            const float u1 = rnd(ggx_seed1);
            const float u2 = rnd(ggx_seed2);
            const float3 m_local = sampleGGXVNDF(alpha, view_local, u1, u2);
            shading_normal = normalize(
                m_local.x * tangent + m_local.y * bitangent + m_local.z * normal);
        }
    }

    const float cos_theta_i = fabsf(dot(ray_dir, shading_normal));
    const float sin_theta_i_sq = 1.0f - cos_theta_i * cos_theta_i;
    const float sin_theta_t_sq = eta * eta * sin_theta_i_sq;
```

**Regression guard:** `alpha > ROUGHNESS_EPSILON` is false for every existing reference scene (every dielectric preset sets `roughness = 0.0f`, so `alpha = 0.0f`), so `shading_normal` always equals `normal` for them, and `cos_theta_i`/`sin_theta_i_sq`/`sin_theta_t_sq` compute byte-identically to before this task's change (same expressions, `shading_normal == normal`, no extra RNG draws taken).

- [ ] **Step 6: Implement — use `shading_normal` in `reflect_dir` and the refract formula**

Replace `reflect_dir` (`caustics_ppm.cu:1093-1097`, now shifted later by Step 5's insertion — locate by content, not line number):

```cpp
    const float3 reflect_dir = normalize(make_float3(
        ray_dir.x + 2.0f * cos_theta_i * normal.x,
        ray_dir.y + 2.0f * cos_theta_i * normal.y,
        ray_dir.z + 2.0f * cos_theta_i * normal.z
    ));
```

with (`normal` → `shading_normal`):

```cpp
    const float3 reflect_dir = normalize(make_float3(
        ray_dir.x + 2.0f * cos_theta_i * shading_normal.x,
        ray_dir.y + 2.0f * cos_theta_i * shading_normal.y,
        ray_dir.z + 2.0f * cos_theta_i * shading_normal.z
    ));
```

And the refract formula inside the Russian-roulette `else` branch (`caustics_ppm.cu:1145-1149`):

```cpp
            new_dir = normalize(make_float3(
                eta * ray_dir.x + (eta * cos_theta_i - cos_theta_t) * normal.x,
                eta * ray_dir.y + (eta * cos_theta_i - cos_theta_t) * normal.y,
                eta * ray_dir.z + (eta * cos_theta_i - cos_theta_t) * normal.z
            ));
```

with (`normal` → `shading_normal`):

```cpp
            new_dir = normalize(make_float3(
                eta * ray_dir.x + (eta * cos_theta_i - cos_theta_t) * shading_normal.x,
                eta * ray_dir.y + (eta * cos_theta_i - cos_theta_t) * shading_normal.y,
                eta * ray_dir.z + (eta * cos_theta_i - cos_theta_t) * shading_normal.z
            ));
```

Do **not** change any other reference to `normal` in the function (there are none downstream of these three uses — confirm with `grep -n 'normal\.' caustics_ppm.cu` restricted to this function's line range before committing).

**Regression guard:** identical reasoning to Step 5 — `shading_normal == normal` whenever `alpha <= ROUGHNESS_EPSILON`, so these two expressions are byte-identical to their pre-Phase-3 form for every existing scene.

- [ ] **Step 7: Rebuild native and run the new test**

```bash
sbt "project optixJni" nativeCompile
sbt "testOnly io.github.lene.optix.caustics.CausticsRoughGlassSuite"
```

Expected: PASS, `withClue` showing `roughStddev` clearly below `smoothStddev`. If not, apply systematic-debugging — check `view_local.z >= 0` actually holds (it must, since `dot(view_world, normal) = -dot(ray_dir, normal) = cos_theta_i > 0` given the TIR/grazing guards elsewhere in the function), and that `roughness=0.4` really reaches `InstanceMaterial.roughness` for the sphere instance (it does — `addSphereInstance` marshals the full `Material` struct, same path Task-agnostic PBR shading already relies on).

- [ ] **Step 8: Run the existing caustics suites to confirm zero reference drift**

```bash
sbt "testOnly io.github.lene.optix.caustics.CausticsReferenceSuite"
sbt "testOnly io.github.lene.optix.caustics.ReferenceMatchSuite"
sbt "testOnly io.github.lene.optix.caustics.CausticsValidationSuite"
sbt "testOnly io.github.lene.optix.caustics.CausticsCoverageSuite"
sbt "testOnly io.github.lene.optix.caustics.CausticsWallReceiverSuite"
sbt "testOnly io.github.lene.optix.caustics.CausticsMeshReceiverSuite"
sbt "testOnly io.github.lene.optix.caustics.MultiObjectCausticsSuite"
sbt "testOnly io.github.lene.optix.caustics.AreaLightCausticsSuite"
```

Expected: all PASS unchanged. Any failure means the `alpha > ROUGHNESS_EPSILON` gate is leaking into the `roughness=0.0f` path — STOP, systematic-debugging, do not proceed to Task 2.

- [ ] **Step 9: Commit**

```bash
git add src/main/native/shaders/caustics_ppm.cu \
    src/main/native/include/OptiXData.h \
    src/test/scala/io/github/lene/optix/caustics/CausticsRoughGlassSuite.scala
git commit -m "feat(caustics): GGX-VNDF rough/frosted refraction gated on InstanceMaterial.roughness"
```

---

### Task 2: Energy-conservation lock across a roughness sweep

**Repo:** optix-jni

**Files:**
- Modify: `src/test/scala/io/github/lene/optix/caustics/CausticsRoughGlassSuite.scala`

**Interfaces:**
- Consumes: `setupScene` from Task 1; `CausticsStats.energyConservationError` (existing derived method, `OptiXRenderer.scala:145-149` — do not reimplement the arithmetic).

VNDF importance sampling is energy-preserving by construction, so this task should pass once Task 1's math is correct — it exists to *prove* that, using the exact same regression-guard pattern `CausticsCoverageSuite` already established (`energyConservationError` bounded + deterministic across two identical runs).

- [ ] **Step 1: Add the energy-conservation assertion**

Append to `CausticsRoughGlassSuite.scala`, before `end CausticsRoughGlassSuite`:

```scala
  // Mirrors CausticsCoverageSuite's regression-guard pattern: energyConservationError is NOT a
  // physical energy-conservation bound (totalFluxDeposited is raw pre-normalization flux, see
  // CausticsCoverageSuite's comment) -- this locks the RATIO so future accounting drift is
  // caught, across a roughness sweep so GGX sampling doesn't introduce its own drift.
  private val MaxEnergyConservationErrorRatio: Double = 780.0

  it should "report a bounded, deterministic energy-conservation error across a roughness sweep" in:
    if runningUnderSanitizer then cancel("Skipped under compute-sanitizer (too slow)")
    for roughness <- Seq(0.0f, 0.2f, 0.5f, 0.8f) do
      val glass = Material(Color(0.95f, 0.95f, 1.0f, 0.5f), ior = Const.iorGlass, roughness = roughness)
      setupScene(glass)
      renderer.enableCaustics(photonsPerIter, causticIterations)
      val statsA = { renderer.renderWithStats(imageSize).get; renderer.getCausticsStats }
      setupScene(glass)
      renderer.enableCaustics(photonsPerIter, causticIterations)
      val statsB = { renderer.renderWithStats(imageSize).get; renderer.getCausticsStats }
      withClue(s"roughness=$roughness energyConservationError A=${statsA.energyConservationError} " +
        s"B=${statsB.energyConservationError}; must be bounded and deterministic. ") {
        statsA.totalFluxEmitted should be > 0.0
        statsA.energyConservationError shouldBe statsB.energyConservationError +- 1e-6
        statsA.energyConservationError should be < MaxEnergyConservationErrorRatio
      }
```

- [ ] **Step 2: Run; confirm PASS**

```bash
sbt "testOnly io.github.lene.optix.caustics.CausticsRoughGlassSuite"
```

Expected: PASS for all four roughness values. If it fails at any nonzero roughness (but passes at 0.0), there's a real double-counting/lost-energy bug in Task 1's `shading_normal` substitution — apply systematic-debugging (check `depositPhoton`/`applyPhotonBeerLambert` calls, which are unmodified by Task 1 and use `hit_point`/`t`, not `shading_normal` — confirm nothing downstream of the RR split still reads the old `normal` accidentally).

- [ ] **Step 3: Commit**

```bash
git add src/test/scala/io/github/lene/optix/caustics/CausticsRoughGlassSuite.scala
git commit -m "test(caustics): lock energy conservation across a roughness sweep"
```

---

### Task 3: Edge-case hardening — grazing angles, roughness boundary, rough+dispersive interaction

**Repo:** optix-jni

**Files:**
- Modify: `src/main/native/shaders/caustics_ppm.cu` (only if a real bug surfaces — see Step 2)
- Modify: `src/test/scala/io/github/lene/optix/caustics/CausticsRoughGlassSuite.scala`

**Interfaces:**
- Consumes: `setupScene` from Task 1.

- [ ] **Step 1: Add three targeted edge-case tests**

Append to `CausticsRoughGlassSuite.scala`, before `end CausticsRoughGlassSuite`:

```scala
  it should "not produce NaN pixels for a rough sphere at grazing light incidence" in:
    if runningUnderSanitizer then cancel("Skipped under compute-sanitizer (too slow)")
    val roughGlass = Material(Color(0.95f, 0.95f, 1.0f, 0.5f), ior = Const.iorGlass, roughness = 0.6f)
    renderer.clearAllInstances()
    renderer.addSphereInstance(sphereTransform, roughGlass)
    renderer.clearPlanes()
    renderer.addPlane(1, positive = true, Const.defaultFloorPlaneY)
    renderer.setCamera(
      Vector[3](0.0f, 4.0f, 8.0f), Vector[3](0.0f, 0.0f, 0.0f), Vector[3](0.0f, -1.0f, 0.0f), 45.0f)
    // Near-parallel-to-silhouette light direction (grazing incidence on the sphere), not
    // straight down like setupScene's default -- exercises cos_theta_i near zero.
    renderer.setLight(Vector[3](0.95f, -0.05f, -0.3f), lightIntensity)
    renderer.enableCaustics(photonsPerIter, causticIterations)
    val result = renderer.renderWithStats(imageSize).get
    val stats = renderer.getCausticsStats
    withClue(s"photonsDeposited=${stats.photonsDeposited}; grazing incidence on rough glass must " +
      "not crash or silently deposit zero photons. ") {
      stats.photonsDeposited should be > 0L
    }
    val hasNaNPixel = (0 until imageSize.height).exists(y =>
      (0 until imageSize.width).exists(x =>
        val p = ImageValidation.getRGBAt(result.image, imageSize, x, y)
        p.r.isNaN || p.g.isNaN || p.b.isNaN))
    hasNaNPixel shouldBe false

  it should "not crash or produce domain errors at roughness=1.0 (fully diffuse boundary)" in:
    if runningUnderSanitizer then cancel("Skipped under compute-sanitizer (too slow)")
    val maxRoughGlass = Material(Color(0.95f, 0.95f, 1.0f, 0.5f), ior = Const.iorGlass, roughness = 1.0f)
    setupScene(maxRoughGlass)
    renderer.enableCaustics(photonsPerIter, causticIterations)
    noException should be thrownBy renderer.renderWithStats(imageSize).get

  it should "preserve dispersion's spectral spread when the instance is also rough" in:
    if runningUnderSanitizer then cancel("Skipped under compute-sanitizer (too slow)")
    val baseColor = Color(0.95f, 0.95f, 1.0f, 0.5f)
    val roughPlain = Material(baseColor, ior = Const.iorGlass, roughness = 0.4f, dispersion = 0.0f)
    val roughDispersive = Material(baseColor, ior = Const.iorGlass, roughness = 0.4f, dispersion = 1.0f)

    def channelDelta(material: Material): (Double, Double, Double) =
      setupScene(material)
      renderer.enableCaustics(photonsPerIter, causticIterations)
      val onResult = renderer.renderWithStats(imageSize).get
      val onPixels = for
        y <- 0 until imageSize.height
        x <- 0 until imageSize.width
      yield ImageValidation.getRGBAt(onResult.image, imageSize, x, y)
      setupScene(material)
      renderer.disableCaustics()
      val offResult = renderer.renderWithStats(imageSize).get
      val offPixels = for
        y <- 0 until imageSize.height
        x <- 0 until imageSize.width
      yield ImageValidation.getRGBAt(offResult.image, imageSize, x, y)
      (
        (onPixels.map(_.r).sum - offPixels.map(_.r).sum).toDouble / onPixels.length,
        (onPixels.map(_.g).sum - offPixels.map(_.g).sum).toDouble / onPixels.length,
        (onPixels.map(_.b).sum - offPixels.map(_.b).sum).toDouble / onPixels.length
      )

    val (pr, pg, pb) = channelDelta(roughPlain)
    val plainSpread = math.max(pr, math.max(pg, pb)) - math.min(pr, math.min(pg, pb))
    val (dr, dg, db) = channelDelta(roughDispersive)
    val dispSpread = math.max(dr, math.max(dg, db)) - math.min(dr, math.min(dg, db))
    withClue(s"channel spread: rough+dispersion-off=$plainSpread rough+dispersion-on=$dispSpread; " +
      "roughness must not silently suppress dispersion's chromatic spread. ") {
      dispSpread should be > plainSpread
    }
```

- [ ] **Step 2: Run; confirm PASS, or fix real bugs found**

```bash
sbt "testOnly io.github.lene.optix.caustics.CausticsRoughGlassSuite"
```

If a NaN or crash surfaces: apply systematic-debugging. The two known VNDF failure modes to check first: (a) `sin_theta_t_sq` computed from `shading_normal` can exceed the TIR threshold differently than the geometric normal would — this is expected physics (rough TIR), not a bug, as long as it's handled by the existing `sin_theta_t_sq > 1.0f` branch (it is, unchanged); (b) in rare tail cases near the `alpha` boundary, `sampleGGXVNDF`'s returned `m_local.z` can be `fmaxf(0.0f, nh.z)`-clamped to exactly 0, which is already guarded (Step 4 of Task 1 clamps this explicitly). If a genuine bug is found, fix it directly in `caustics_ppm.cu` and re-run this task's tests plus Task 1/2's tests before committing.

- [ ] **Step 3: Run the full existing caustics suite set — confirm zero reference drift**

```bash
sbt "testOnly io.github.lene.optix.caustics.CausticsReferenceSuite"
sbt "testOnly io.github.lene.optix.caustics.CausticsCoverageSuite"
sbt "testOnly io.github.lene.optix.caustics.CausticsWallReceiverSuite"
sbt "testOnly io.github.lene.optix.caustics.CausticsMeshReceiverSuite"
sbt "testOnly io.github.lene.optix.caustics.MultiObjectCausticsSuite"
sbt "testOnly io.github.lene.optix.caustics.AreaLightCausticsSuite"
```

Expected: all PASS unchanged, including `CausticsCoverageSuite`'s TIR/refraction-event counters (bit-identical for `roughness=0.0f` scenes since the gate makes this task's code paths entirely unreachable there).

- [ ] **Step 4: Commit**

```bash
git add src/main/native/shaders/caustics_ppm.cu \
    src/test/scala/io/github/lene/optix/caustics/CausticsRoughGlassSuite.scala
git commit -m "test(caustics): edge-case hardening for GGX rough refraction (grazing, roughness=1, +dispersion)"
```

---

### Task 4: menger integration test + manual scene

**Repo:** menger

**Files:**
- Modify: `scripts/integration-tests.sh`, `scripts/manual-test.sh`, `menger/build.sbt` (`optixJniDependency`)
- Create: `scripts/reference-images/frosted_glass_caustics.png` (generated)

**Interfaces:**
- Consumes: `roughness=VALUE` — already a fully-wired `ObjectSpec` override (`ObjectSpec.scala:405,414`, "override roughness (only with material preset)") reaching `InstanceMaterial.roughness` for any preset including `glass`. No new CLI/DSL/JNI plumbing needed.

- [ ] **Step 1: Bump menger's optix-jni pin**

In `menger/build.sbt`, change `optixJniDependency` to the Phase 3 release version (set once Task 6 publishes it — do this step after Task 6, not before; listed here for file-scope completeness).

- [ ] **Step 2: Add the integration test function**

After `test_mesh_receiver_caustics()` (`scripts/integration-tests.sh:586-594`):

```bash
test_frosted_glass_caustics() {
    echo "Frosted-glass caustics (rough dielectric — spread vs sharp Caustics scene):"
    TIMEOUT=$CAUSTICS_TIMEOUT run_test "frosted glass caustics" \
        --objects type=sphere:material=glass:roughness=0.4:size=1:pos=0,0,0 \
        --caustics --caustics-photons 1000 --caustics-iterations 1 \
        --plane y:-2 --light point:0,10,0:500
}
```

Register it alongside the other caustics tests in the run-list (same block containing `test_mesh_receiver_caustics`):

```bash
    test_mesh_receiver_caustics
    test_frosted_glass_caustics
```

- [ ] **Step 3: Confirm initial MISSING-reference failure, then generate + verify determinism**

```bash
sbt "project mengerApp" stage
BIN=$(pwd)/menger-app/target/universal/stage/bin/menger-app
PARALLEL_MODE=false ./scripts/integration-tests.sh "$BIN" --filter "frosted glass caustics"
```

Expected: MISSING reference (fail) — proves the test runs and reaches the comparison.

```bash
PARALLEL_MODE=false ./scripts/integration-tests.sh "$BIN" --update-references --filter "frosted glass caustics"
git status --short scripts/reference-images/   # must show ONLY the 1 new PNG
PARALLEL_MODE=false ./scripts/integration-tests.sh "$BIN" --filter "frosted glass caustics"
```

Expected on the re-run: `- match (diff: 0%)`. If not deterministic, STOP and investigate before committing the reference.

- [ ] **Step 4: Add the manual scene**

Append to `scripts/manual-test.sh` after the existing `164-mesh-receiver-caustics` entry (`:371`):

```bash
run_test "Frosted-glass caustics (rough dielectric — spread/blurred caustic vs the sharp Caustics scene above)" "-o --objects type=sphere:material=glass:roughness=0.4 --camera-pos 0,1.5,6 --camera-lookat 0,0,0 --light point:0,10,0:500 --plane y:-2 --plane-color cccccc --caustics --caustics-photons 500000 --caustics-iterations 20 -s $OUTPUT_DIR/165-frosted-glass-caustics.png"
```

- [ ] **Step 5: Smoke-run the manual scene**

```bash
__GL_THREADED_OPTIMIZATIONS=0 xvfb-run -a "$BIN" --headless \
    --objects type=sphere:material=glass:roughness=0.4 --camera-pos 0,1.5,6 --camera-lookat 0,0,0 \
    --light point:0,10,0:500 --plane y:-2 --plane-color cccccc \
    --caustics --caustics-photons 200000 --caustics-iterations 8 --save-name /tmp/165-frosted-glass.png
```

Expected: `/tmp/165-frosted-glass.png` written; open it and confirm a visibly softer/wider caustic than the sharp `57-caustics.png` reference.

- [ ] **Step 6: Commit**

```bash
git add menger/build.sbt scripts/integration-tests.sh scripts/manual-test.sh \
    scripts/reference-images/frosted_glass_caustics.png
git commit -m "test(caustics): integration + manual scene for frosted-glass rough refraction"
```

---

### Task 5: optix-jni gate + version bump

**Repo:** optix-jni

**Files:**
- Modify: `build.sbt` (`version :=`), `CHANGELOG.md`

- [ ] **Step 1: Bump version and add a CHANGELOG entry**

In `build.sbt`, change `version := "0.1.18"` to `version := "0.1.19"`. Prepend to `CHANGELOG.md`:

```markdown
## [0.1.19] - <today>

### Added

- Rough/frosted refraction: GGX-VNDF microfacet importance sampling (Heitz 2018) in the photon
  closest-hit path, gated on `InstanceMaterial.roughness > 0` (alpha = roughness^2). Perturbs
  both the reflect and refract directions off one sampled microfacet normal, using the existing
  Fresnel/Russian-roulette split unchanged. Smooth glass (roughness=0.0, every existing preset)
  is byte-identical to pre-0.1.19 behavior.
```

- [ ] **Step 2: Run the full pre-push gate**

```bash
BRANCH=$(git rev-parse --abbrev-ref HEAD); SHA=$(git rev-parse HEAD)
printf 'refs/heads/%s %s refs/heads/%s 0000000000000000000000000000000000000000\n' "$BRANCH" "$SHA" "$BRANCH" | ./.git_hooks/pre-push 2>&1 | tee /tmp/optix-rough-refraction-gate.log
```

Expected: `Tests: OK`, gtest unchanged, `Scalafix: OK`, `cppcheck: OK`. Fix any failure before proceeding.

- [ ] **Step 3: Commit**

```bash
git add build.sbt CHANGELOG.md
git commit -m "chore: bump optix-jni 0.1.18 -> 0.1.19 (GGX-VNDF rough refraction)"
```

---

### Task 6: Publish 0.1.19 and verify live on Maven Central

**Repo:** optix-jni

**Files:** none (release + verification only).

- [ ] **Step 1: Push, open PR, merge (after explicit user confirmation of the push)**

Standard flow: push the feature branch, `gh pr create`, wait for CI, merge after user confirmation.

- [ ] **Step 2: Tag and trigger publish**

```bash
git tag v0.1.19
git push origin v0.1.19
gh workflow run ci.yml --ref v0.1.19
```

- [ ] **Step 3: Verify all 4 artifacts are live directly against Maven Central**

```bash
for suffix in "" "-sources" "-javadoc"; do
  curl -s -o /dev/null -w "%{http_code} optix-jni-0.1.19${suffix}.jar\n" \
    "https://repo1.maven.org/maven2/io/github/lene/optix-jni/0.1.19/optix-jni-0.1.19${suffix}.jar"
done
curl -s -o /dev/null -w "%{http_code} optix-jni-0.1.19.pom\n" \
  "https://repo1.maven.org/maven2/io/github/lene/optix-jni/0.1.19/optix-jni-0.1.19.pom"
```

Expected: all four lines report `200`. If any is not `200` yet, wait and re-check (Central sync can lag minutes) before concluding it failed.

---

### Task 7: menger version bump + DoD gate + push

**Repo:** menger

**Files:**
- Modify: `menger-app/build.sbt`, `.gitlab-ci.yml`, `menger-app/src/main/scala/menger/MengerCLIOptions.scala`, `docs/guide/user-guide.md`, `docs/USER_GUIDE.md`, `CHANGELOG.md`, `menger/build.sbt` (`optixJniDependency` — finish Task 4 Step 1 here, pinned to the now-published `0.1.19`)

- [ ] **Step 1: Bump menger's optix-jni pin to 0.1.19**

In `menger/build.sbt`, set `optixJniDependency` to `0.1.19`.

- [ ] **Step 2: Ask the user for the next menger version number**

Do not infer it (standing constraint). The prior version in this roadmap was 0.8.4; confirm the next number with the user before editing any file.

- [ ] **Step 3: Bump the version across all five files and add a CHANGELOG section**

Replace the old version string with the confirmed new one in: `menger-app/build.sbt` (`version :=`), `.gitlab-ci.yml` (`DEPLOYABLE_VERSION`), `MengerCLIOptions.scala` (`version("menger v... ")`), `docs/guide/user-guide.md` (`**Version**:`), `docs/USER_GUIDE.md` (`**Version**:`). Prepend to `CHANGELOG.md`:

```markdown
## [<new version>] - <today>

### Added

- Frosted/rough-glass caustics: `roughness=VALUE` on a glass material now spreads and softens
  its caustic (GGX-VNDF microfacet sampling). Consumes optix-jni 0.1.19.
```

- [ ] **Step 4: Run the full DoD gate**

```bash
LOCAL=$(git rev-parse HEAD)
printf 'refs/heads/feature/caustics-rough-refraction %s refs/heads/feature/caustics-rough-refraction <REMOTE_SHA>\n' "$LOCAL" | ./.git_hooks/pre-push 2>&1 | tee /tmp/menger-rough-refraction-gate.log
```

(Use the current `origin/feature/...` SHA as `<REMOTE_SHA>`, or the merge-base with `origin/main` for a fresh branch.) Expected: `Passed: N/N` integration (including the new scene), coverage ≥ 80%, Valgrind/cppcheck/clang-tidy pass, `MENGER_PREPUSH_RC=0`. Fix any failure in this same branch before pushing.

- [ ] **Step 5: Commit**

```bash
git add menger/build.sbt menger-app/build.sbt .gitlab-ci.yml \
    menger-app/src/main/scala/menger/MengerCLIOptions.scala \
    docs/guide/user-guide.md docs/USER_GUIDE.md CHANGELOG.md
git commit -m "chore: bump menger version (frosted-glass rough refraction, optix-jni 0.1.19)"
```

- [ ] **Step 6: Push (explicit user confirmation required)**

Await explicit "push" confirmation from the user before pushing. Monitor the resulting GitLab pipeline to completion; fix any failure.

---

## Notes for the executor

- Tasks 1-3 are TDD in the normal sense (RED first, confirmed failing for the right reason, then GREEN). Tasks 4-7 are integration/release mechanics — Task 4's "test" is its own RED/GREEN (missing → generated reference), Tasks 5-7's "test" is the gate/pipeline itself.
- The load-bearing simplification of this plan versus the phase's original scoping: GGX sampling's two uniform draws come from fresh ad-hoc TEA hashes (same technique as the existing `rr_seed`), **not** from plumbing the true photon RNG seed through a new payload register. This was confirmed viable by direct inspection of `tracePhoton`'s payload layout (all 11 registers already used: flux/origin/dir/flags/wavelength) and the precedent that `rr_seed` already solves the identical problem the same way. Do not "simplify" this into payload plumbing without discussing the tradeoff with the user first — it's unnecessary complexity for this phase.
- `shading_normal` is the only new state threaded through the existing Fresnel/reflect/refract block; `normal` (the geometric, oriented normal) still exists and is used to build the microfacet-sampling tangent frame. Do not delete `normal` or rename it away — the ONB is built around it.
- Push order once all gates are green: optix-jni branch → PR → merge → tag → publish → verify Maven Central (Tasks 5-6) → **then** menger branch → push (Task 7). Await explicit user confirmation before any push.
- Task 3's edge-case tests are written expecting Task 1's implementation to already handle them correctly (VNDF sampling is well-behaved by construction at the boundaries tested). If a real bug surfaces, fix it in Task 3 rather than treating the test as wrong — see Task 3 Step 2's guidance on the two known VNDF tail-case failure modes to check first.
