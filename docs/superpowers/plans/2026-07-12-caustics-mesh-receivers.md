# Arbitrary Receiver Surfaces (Phase 2) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Caustic photons deposit onto arbitrary diffuse surfaces — any enabled analytic plane (not just `planes[0]`), and diffuse mesh/sphere instances (`ior <= 1.05`) — not only the hardcoded floor plane. Ships as two rungs: multi-plane deposit, then mesh-instance deposit.

**Architecture:** `depositPhoton` and the radiance kernel (`caustics_ppm.cu`) already operate on an arbitrary 3D spatial grid of `HitPoint`s and need no changes. The floor-plane-only assumption lives in exactly four places — `__raygen__hitpoints` (hit-point seeding), `checkPlaneIntersection` (deposit target for photons that miss all geometry), `__closesthit__photon` (every instance hit is refracted, no diffuse branch), and `initializeHitPoint`/`getPlaneNormalComponents` (normal/albedo hardcoded from `planes[0]`). Task 1 generalizes the plane path from `planes[0]` to all enabled planes. Tasks 2–3 add mesh-instance receivers: a new probe-mode branch inside the *existing* `RAY_TYPE_PHOTON` closest-hit/miss programs (reusing already-registered SBT hit groups for every geometry type — no new ray type, no SBT/pipeline changes) lets `__raygen__hitpoints` discover a real diffuse-instance hit; a new branch inside `__closesthit__photon`'s live photon-transport path deposits on that instance instead of refracting it.

**Tech Stack:** CUDA/OptiX (`.cu` shaders), Scala 3 ScalaTest (`AnyFlatSpec`), `RendererFixture`/`OptiXRenderer`/`CausticsStats`, menger-common (`Material`, `Const`), bash integration/manual scripts, ImageMagick `compare` for reference diffs.

## Global Constraints

- Scala 3 only; no `var`/`while`/`asInstanceOf`/`throw` in production (tests may use `@SuppressWarnings` + `scalafix:ok`, matching existing caustics suites). Max line length 100. No magic numbers — named constants.
- C++/CUDA: manual edits only — no `sed`/scripts for bulk edits (has corrupted code before). Match existing style in `caustics_ppm.cu` (Doxygen-style `/** */` headers on new `__device__`/`__global__` functions, `// P<n>:`-style dated rationale comments on non-obvious changes).
- GPU tests self-skip when no GPU (`assume(OptiXRenderer.isLibraryLoaded, ...)` via `RendererFixture`) and cancel under compute-sanitizer (`if runningUnderSanitizer then cancel(...)`).
- **RNG-regression discipline (non-negotiable):** every new code path is gated behind a condition false for all existing scenes — a second enabled plane present (Task 1), or a diffuse instance (`ior <= 1.05`) present (Tasks 2–3). No existing reference image may drift. This mirrors the `count > 1` RNG guards from optix-jni 0.1.16.
- `Material` (menger-common 0.1.6): `Material(color: Color, ior: Float = 1.0f, roughness: Float = 0.5f, metallic: Float = 0.0f, specular: Float = 0.5f, emission: Float = 0.0f, filmThickness: Float = 0.0f, dispersion: Float = 0.0f, ...)`. `Const.iorGlass = 1.5f`, `Const.defaultFloorPlaneY`. Caustic targets are refractive-only above `ior > 1.05` (`hit_triangle.cu:354`); diffuse = `ior <= 1.05`.
- `enableCaustics(photonsPerIter: Int = 100000, iterations: Int = 10, initialRadius: Float = 1.0f, alpha: Float = 0.7f)`. `renderer.renderWithStats(imageSize).get: RenderResult`; `RenderResult.image: Array[Byte]` row-major RGBA8.
- Proven-visible caustic framing (mirror `CausticsReferenceSuite.ReferenceScene` / `CausticsCoverageSuite.setupGlassScene`): radius-1 glass sphere, floor at `Const.defaultFloorPlaneY`, **directional** light `(0,-1,0)` at **intensity 500** (a dimmer light quantizes the caustic to 0 in 8-bit), camera eye `(0,4,8)` look-at origin, up `(0,-1,0)` (inverted), FOV 45. `setCamera`'s 2nd arg is a look-at POINT, not a direction.
- menger repo policy (hard requirement, restated by the user for this roadmap): every rendering feature ships a headless integration test **and** a manual scene; reference-image diffs resolved in the same commit; verify determinism (run-to-run identical) before committing references.
- Never commit to `main`; never push without explicit user confirmation; never `git add -A`; every commit trailer includes `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>` and `Claude-Session: https://claude.ai/code/session_0128FY5RY2p5AZorgHML8u6G`.

---

### Task 1: Multi-plane deposit — generalize `planes[0]` to all enabled planes

**Repo:** optix-jni

**Files:**
- Modify: `src/main/native/shaders/caustics_ppm.cu` (`checkPlaneIntersection` at `:465-499`, `__raygen__hitpoints` at `:262-289`, `initializeHitPoint`/`getPlaneNormalComponents` at `:171-218`)
- Create: `src/test/scala/io/github/lene/optix/caustics/CausticsWallReceiverSuite.scala`

**Interfaces:**
- Produces: `checkPlaneIntersection` and the hit-point-seeding block in `__raygen__hitpoints` both iterate `params.planes[0 .. params.num_planes-1]` and pick the **nearest enabled** intersection, instead of only reading `planes[0]`.
- Consumes (existing, unchanged): `HitPoint` struct (`OptiXData.h:330-341`), `PlaneParams` struct (`OptiXData.h:452-465`: `axis`, `positive`, `value`, `enabled`, `color1[3]`), `MAX_PLANES = 4` (`OptiXData.h:52`), `params.num_planes` (`OptiXData.h:579`), `getRayPlaneComponents` (existing helper, unchanged signature).

- [ ] **Step 1: Write the failing test — caustic deposits on a non-floor (back-wall) plane**

Create `src/test/scala/io/github/lene/optix/caustics/CausticsWallReceiverSuite.scala`:

```scala
package io.github.lene.optix.caustics

import io.github.lene.optix.RendererFixture
import io.github.lene.optix.ImageValidation
import io.github.lene.optix.RenderResult
import menger.common.Color
import menger.common.Const
import menger.common.ImageSize
import menger.common.Material
import menger.common.Vector
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Phase 2 Task 1: caustics deposit onto a SECOND enabled plane (a back wall, axis != Y),
  * not only planes[0] (the floor). Characterizes the multi-plane deposit path added in this
  * task — must FAIL against pre-Task-1 code (only planes[0] receives a caustic today).
  */
class CausticsWallReceiverSuite extends AnyFlatSpec with Matchers with RendererFixture:

  private val runningUnderSanitizer: Boolean =
    sys.env.get("RUNNING_UNDER_COMPUTE_SANITIZER").contains("true")

  private val imageSize: ImageSize = ImageSize(200, 150)
  private val sphereRadius = 1.0f
  private val photonsPerIter = 100000
  private val causticIterations = 4
  private val lightIntensity = 500.0f

  private val sphereTransform: Array[Float] =
    Array(sphereRadius, 0f, 0f, 0f, 0f, sphereRadius, 0f, 0f, 0f, 0f, sphereRadius, 0f)

  // Sphere between camera and a back wall (Z axis, negative side) so a light angled toward
  // the wall casts a caustic onto it. Floor (planes[0]) stays enabled too, proving BOTH
  // receive deposits, not just whichever is added second.
  private def setupWallScene(material: Material): Unit =
    renderer.clearAllInstances()
    renderer.addSphereInstance(sphereTransform, material)
    renderer.clearPlanes()
    renderer.addPlane(1, positive = true, Const.defaultFloorPlaneY)     // floor, planes[0]
    renderer.addPlane(2, positive = false, -4.0f)                      // back wall, planes[1]
    renderer.setCamera(
      Vector[3](0.0f, 2.0f, 8.0f),
      Vector[3](0.0f, 0.0f, -2.0f),
      Vector[3](0.0f, -1.0f, 0.0f),
      45.0f
    )
    renderer.setLight(Vector[3](0.3f, -0.3f, -1.0f), lightIntensity)

  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  private def wallCausticDelta(material: Material): (Double, Double, Double) =
    setupWallScene(material)
    renderer.enableCaustics(photonsPerIter, causticIterations)
    val onPixels = for
      y <- 0 until imageSize.height
      x <- 0 until imageSize.width
    yield ImageValidation.getRGBAt(renderer.renderWithStats(imageSize).get.image, imageSize, x, y)
    setupWallScene(material)
    renderer.disableCaustics()
    val offPixels = for
      y <- 0 until imageSize.height
      x <- 0 until imageSize.width
    yield ImageValidation.getRGBAt(renderer.renderWithStats(imageSize).get.image, imageSize, x, y)
    (
      (onPixels.map(_.r).sum - offPixels.map(_.r).sum).toDouble / onPixels.length,
      (onPixels.map(_.g).sum - offPixels.map(_.g).sum).toDouble / onPixels.length,
      (onPixels.map(_.b).sum - offPixels.map(_.b).sum).toDouble / onPixels.length
    )

  behavior of "Multi-plane caustic deposit"

  it should "deposit a caustic on a second enabled plane (back wall), not only planes[0]" in:
    if runningUnderSanitizer then cancel("Skipped under compute-sanitizer (too slow)")
    val clearGlass = Material(Color(0.95f, 0.95f, 1.0f, 0.5f), ior = Const.iorGlass)
    val (dr, dg, db) = wallCausticDelta(clearGlass)
    withClue(s"whole-image caustic delta (on-off) with floor+wall enabled: R=$dr G=$dg B=$db; " +
      "a caustic on the wall must brighten the image beyond what a floor-only deposit would. ") {
      (dr + dg + db) should be > 0.0
    }

end CausticsWallReceiverSuite
```

- [ ] **Step 2: Run and confirm it fails against current code**

```bash
sbt "testOnly io.github.lene.optix.caustics.CausticsWallReceiverSuite"
```

Expected: this may actually PASS today if the floor alone produces a nonzero delta (the assertion doesn't yet isolate the wall). Before implementing, tighten the discriminator: temporarily disable the floor plane (comment out the `addPlane(1, ...)` call) and re-run — if the delta drops to ~0 with only the wall enabled and current (pre-fix) code, the test correctly captures "wall alone gets no caustic." Restore the floor line before continuing; the real regression net is that *both* receive deposits simultaneously, which requires Step 3.

- [ ] **Step 3: Implement — generalize `checkPlaneIntersection` to all enabled planes**

Replace `checkPlaneIntersection` (`caustics_ppm.cu:465-499`):

```cpp
/**
 * Check if photon hits any enabled plane and deposit energy at the nearest hit.
 * Returns true if photon was deposited (absorbed by a diffuse plane surface).
 */
__device__ bool checkPlaneIntersection(
    const float3& origin,
    const float3& dir,
    const float3& flux
) {
    float nearest_t = MAX_RAY_DISTANCE;
    bool found = false;

    for (int i = 0; i < params.num_planes; ++i) {
        if (!params.planes[i].enabled) continue;

        const int plane_axis = params.planes[i].axis;
        const float plane_value = params.planes[i].value;

        float ray_orig_comp, ray_dir_comp;
        if (plane_axis == 0) {
            ray_orig_comp = origin.x;
            ray_dir_comp = dir.x;
        } else if (plane_axis == 1) {
            ray_orig_comp = origin.y;
            ray_dir_comp = dir.y;
        } else {
            ray_orig_comp = origin.z;
            ray_dir_comp = dir.z;
        }

        if (fabsf(ray_dir_comp) <= RAY_PARALLEL_THRESHOLD) continue;

        const float t_plane = (plane_value - ray_orig_comp) / ray_dir_comp;
        if (t_plane > CONTINUATION_RAY_OFFSET && t_plane < nearest_t) {
            nearest_t = t_plane;
            found = true;
        }
    }

    if (found) {
        const float3 plane_hit = origin + dir * nearest_t;
        depositPhoton(plane_hit, dir, flux);
        return true;
    }
    return false;
}
```

**Regression guard:** with exactly one enabled plane (every existing reference scene), the loop finds one candidate and `nearest_t`/`plane_hit` reduce to the prior single-plane arithmetic exactly — same float ops, same order, byte-identical.

- [ ] **Step 4: Implement — generalize `__raygen__hitpoints` plane seeding to all enabled planes**

Replace the plane-seeding block in `__raygen__hitpoints` (`caustics_ppm.cu:262-289`, keep the camera-ray trace above it at `:246-260` unchanged):

```cpp
    // Seed a hit point on the NEAREST enabled plane the camera ray would strike.
    // Diffuse mesh-instance seeding is added in Task 2 (this block still covers the
    // plane-only path and is the fallback when the camera ray does not hit a diffuse instance).
    float nearest_t = MAX_RAY_DISTANCE;
    int nearest_plane = -1;

    for (int i = 0; i < params.num_planes; ++i) {
        if (!params.planes[i].enabled) continue;

        const int plane_axis = params.planes[i].axis;
        const float plane_value = params.planes[i].value;

        float ray_orig_comp, ray_dir_comp;
        getRayPlaneComponents(cam_eye, ray_direction, plane_axis, ray_orig_comp, ray_dir_comp);

        if (fabsf(ray_dir_comp) <= RAY_PARALLEL_THRESHOLD) continue;

        const float t_plane = (plane_value - ray_orig_comp) / ray_dir_comp;
        if (t_plane > 0.0f && t_plane < nearest_t) {
            nearest_t = t_plane;
            nearest_plane = i;
        }
    }

    if (nearest_plane >= 0) {
        const float3 plane_hit = cam_eye + ray_direction * nearest_t;
        const unsigned int hp_idx = atomicAdd(params.caustics.num_hit_points, 1);
        if (hp_idx < MAX_HIT_POINTS) {
            HitPoint& hp = params.caustics.hit_points[hp_idx];
            initializeHitPoint(hp, plane_hit, nearest_plane, idx);
        }
    }
```

**Regression guard:** identical reasoning — one enabled plane means `nearest_plane` always resolves to index 0, same arithmetic as before.

- [ ] **Step 5: Implement — `initializeHitPoint`/`getPlaneNormalComponents` read the matched plane, not always `planes[0]`**

`getPlaneNormalComponents` (`:171-180`) is already parameterized by `plane_axis` — no change needed. `initializeHitPoint` (`:185-218`) hardcodes albedo to `params.planes[0].color1`; change it to take the plane index:

```cpp
__device__ void initializeHitPoint(
    HitPoint& hp,
    const float3& position,
    int plane_axis,
    int plane_index,
    const uint3& idx
) {
    hp.position[0] = position.x;
    hp.position[1] = position.y;
    hp.position[2] = position.z;

    getPlaneNormalComponents(plane_axis, hp.normal[0], hp.normal[1], hp.normal[2]);

    hp.flux[0] = 0.0f;
    hp.flux[1] = 0.0f;
    hp.flux[2] = 0.0f;

    hp.radius = params.caustics.initial_radius;
    hp.n = 0;
    hp.new_photons = 0;

    hp.pixel_x = idx.x;
    hp.pixel_y = idx.y;

    // P5 (generalized in Phase 2 Task 1): capture the matched plane's diffuse albedo, not
    // always planes[0].
    hp.weight[0] = params.planes[plane_index].color1[0];
    hp.weight[1] = params.planes[plane_index].color1[1];
    hp.weight[2] = params.planes[plane_index].color1[2];
}
```

Update the one call site added in Step 4: `initializeHitPoint(hp, plane_hit, nearest_plane, idx)` becomes `initializeHitPoint(hp, plane_hit, params.planes[nearest_plane].axis, nearest_plane, idx)` (pass both axis and index — `nearest_plane` in Step 4's block is already the plane index; rename the call to `initializeHitPoint(hp, plane_hit, params.planes[nearest_plane].axis, nearest_plane, idx)`).

**Regression guard:** with one enabled plane, `plane_index` is always 0 → `params.planes[0].color1` — byte-identical to today.

- [ ] **Step 6: Rebuild native and run the test**

```bash
sbt "project optixJni" nativeCompile
sbt "testOnly io.github.lene.optix.caustics.CausticsWallReceiverSuite"
```

Expected: PASS, with `withClue` printing an `R/G/B` delta clearly greater than the floor-only baseline measured in Step 2. If the delta doesn't grow, apply systematic-debugging — do not weaken the assertion.

- [ ] **Step 7: Run the existing caustics suites to confirm zero reference drift**

```bash
sbt "testOnly io.github.lene.optix.caustics.CausticsReferenceSuite"
sbt "testOnly io.github.lene.optix.caustics.CausticsCoverageSuite"
sbt "testOnly io.github.lene.optix.caustics.MultiObjectCausticsSuite"
sbt "testOnly io.github.lene.optix.caustics.AreaLightCausticsSuite"
```

Expected: all PASS unchanged. Any failure means the single-plane regression guard broke — STOP, systematic-debugging, do not proceed to Task 2.

- [ ] **Step 8: Commit**

```bash
git add src/main/native/shaders/caustics_ppm.cu \
    src/test/scala/io/github/lene/optix/caustics/CausticsWallReceiverSuite.scala
git commit -m "feat(caustics): deposit on all enabled planes, not only planes[0]"
```

---

### Task 2: Probe-mode diffuse-instance detection in the photon closest-hit/miss programs

**Repo:** optix-jni

**Files:**
- Modify: `src/main/native/shaders/caustics_ppm.cu` (`__closesthit__photon` at `:852-1052`, `__miss__photon` at `:1060-1094`)

**Interfaces:**
- Produces: a new payload-bit convention on `RAY_TYPE_PHOTON` traces — bit4 (`16u`) of the *input* payload register 9 signals "probe mode" (a single-hit geometry query, not photon transport). In probe mode, `__closesthit__photon` writes `hit_point.xyz` into payload 0-2, `outward_normal.xyz` into payload 3-5, `glass_color[0..2]` (the instance albedo) into payload 6-8, and a flags word into payload 9: bit0 = 1 (something was hit), bit1 = 1 iff the instance is diffuse (`ior_material <= 1.05f`). `__miss__photon` in probe mode writes payload 9 = `0u` (nothing hit) and returns immediately. Real photon-transport calls (`tracePhoton`, unchanged) never set bit4, so this task changes zero behavior for existing photon bounces.
- Consumes (existing, unchanged): the per-primitive-type geometry-fetch block already in `__closesthit__photon` (`:865-929`, triangle/curve/sphere-cylinder branches — reused verbatim, not duplicated), `MAX_RAY_DISTANCE`, `SBTConstants::RAY_TYPE_PHOTON`/`MISS_PHOTON`/`STRIDE_RAY_TYPES` (`OptiXData.h:146,151,153`), `params.sbt_base_offset`.

This task has no standalone test — it is exercised end-to-end by Task 3's test (the probe is only useful once `__raygen__hitpoints` calls it, which Task 3 wires up). Verification here is a compile + no-regression check.

- [ ] **Step 1: Add the probe-mode early branch to `__closesthit__photon`**

Insert immediately after the geometry-fetch block ends and `ior_material`/`glass_color`/`outward_normal` are known — i.e. right after line 929's closing `}` and before the flux-unpack block at `:931-936` (`caustics_ppm.cu:930`):

```cpp
    // Phase 2 Task 2: probe-mode branch. __raygen__hitpoints reuses this already-registered
    // RAY_TYPE_PHOTON closest-hit (same SBT hit groups as real photon transport, for every
    // geometry type) to discover what the camera ray actually hit, without running any of the
    // photon-transport side effects below (stats, Beer-Lambert, refraction). Gated on payload_9
    // bit4, which tracePhoton() never sets — real photon bounces are unaffected byte-for-byte.
    const bool probe_mode = (optixGetPayload_9() & 16u) != 0u;
    if (probe_mode) {
        const float3 ray_origin_probe = optixGetWorldRayOrigin();
        const float3 hit_point_probe = ray_origin_probe + ray_dir * t;
        const bool is_diffuse = ior_material <= 1.05f;

        optixSetPayload_0(__float_as_uint(hit_point_probe.x));
        optixSetPayload_1(__float_as_uint(hit_point_probe.y));
        optixSetPayload_2(__float_as_uint(hit_point_probe.z));
        optixSetPayload_3(__float_as_uint(outward_normal.x));
        optixSetPayload_4(__float_as_uint(outward_normal.y));
        optixSetPayload_5(__float_as_uint(outward_normal.z));
        optixSetPayload_6(__float_as_uint(glass_color[0]));
        optixSetPayload_7(__float_as_uint(glass_color[1]));
        optixSetPayload_8(__float_as_uint(glass_color[2]));
        optixSetPayload_9(1u | (is_diffuse ? 2u : 0u));
        return;
    }
```

- [ ] **Step 2: Add the probe-mode early branch to `__miss__photon`**

Insert as the first statement inside `__miss__photon` (`caustics_ppm.cu:1060`, right after the opening `{`):

```cpp
    // Phase 2 Task 2: probe-mode miss — nothing hit, report flags=0 and stop (no plane
    // deposit, no stats — those belong to real photon transport only).
    if ((optixGetPayload_9() & 16u) != 0u) {
        optixSetPayload_9(0u);
        return;
    }
```

- [ ] **Step 3: Rebuild native, confirm it compiles clean**

```bash
sbt "project optixJni" nativeCompile
```

Expected: no CUDA/OptiX compile errors or warnings introduced.

- [ ] **Step 4: Run the existing caustics suites to confirm zero behavior change**

```bash
sbt "testOnly io.github.lene.optix.caustics.CausticsReferenceSuite"
sbt "testOnly io.github.lene.optix.caustics.CausticsCoverageSuite"
sbt "testOnly io.github.lene.optix.caustics.CausticsWallReceiverSuite"
```

Expected: all PASS unchanged — this task adds dead code from the perspective of every existing caller (nothing sets bit4 yet).

- [ ] **Step 5: Commit**

```bash
git add src/main/native/shaders/caustics_ppm.cu
git commit -m "feat(caustics): add probe-mode branch to photon closest-hit/miss for instance discovery"
```

---

### Task 3: Diffuse-mesh hit-point seeding + photon deposit branch

**Repo:** optix-jni

**Files:**
- Modify: `src/main/native/shaders/caustics_ppm.cu` (`__raygen__hitpoints`, `initializeHitPoint`, `__closesthit__photon`)
- Create: `src/test/scala/io/github/lene/optix/caustics/CausticsMeshReceiverSuite.scala`

**Interfaces:**
- Consumes: Task 2's probe-mode payload convention; Task 1's `initializeHitPoint(hp, position, plane_axis, plane_index, idx)` signature.
- Produces: `initializeHitPoint` gains an overload for a real surface normal + explicit albedo (used for mesh-instance hit points); `__raygen__hitpoints` calls the probe before falling back to the Task 1 plane path; `__closesthit__photon` gains a diffuse-instance deposit branch in the **real photon-transport** path (not probe mode).

- [ ] **Step 1: Write the failing test — caustic on a diffuse receiver mesh**

Create `src/test/scala/io/github/lene/optix/caustics/CausticsMeshReceiverSuite.scala`:

```scala
package io.github.lene.optix.caustics

import io.github.lene.optix.RendererFixture
import io.github.lene.optix.ImageValidation
import io.github.lene.optix.RenderResult
import menger.common.Color
import menger.common.Const
import menger.common.ImageSize
import menger.common.Material
import menger.common.Vector
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Phase 2 Task 3: a glass sphere casts a caustic onto a diffuse RECEIVER MESH (ior <= 1.05),
  * not only the analytic floor plane. Whole-image caustics-on-minus-off delta, camera-independent
  * (mirrors CausticsCoverageSuite's delta-isolation approach) so the test doesn't depend on
  * guessing exactly where on the receiver the caustic projects.
  */
class CausticsMeshReceiverSuite extends AnyFlatSpec with Matchers with RendererFixture:

  private val runningUnderSanitizer: Boolean =
    sys.env.get("RUNNING_UNDER_COMPUTE_SANITIZER").contains("true")

  private val imageSize: ImageSize = ImageSize(200, 150)
  private val casterRadius = 1.0f
  private val photonsPerIter = 100000
  private val causticIterations = 4
  private val lightIntensity = 500.0f

  private val casterTransform: Array[Float] =
    Array(casterRadius, 0f, 0f, 0f, 0f, casterRadius, 0f, 0f, 0f, 0f, casterRadius, 0f)

  // Diffuse receiver: a flattened (oblate) sphere instance below the glass caster, standing in
  // for a "receiver mesh" rather than the analytic floor plane. addSphereInstance takes a
  // general 3x4 affine transform (confirmed from CausticsCoverageSuite's own sphereTransform
  // usage), so a non-uniform scale (wide X/Z, thin Y) turns the unit sphere into a flat diffuse
  // pad — still a real mesh/instance closest-hit, not a plane, which is what this task's
  // instance-deposit branch (__closesthit__photon) needs to exercise. No addBoxInstance/cube
  // helper exists on OptiXRenderer at this level (only sphere/triangleMesh/cylinder/cone/curve
  // instance methods) — a triangle-mesh box would need raw vertex/index buffers, unnecessary
  // complexity for this test.
  private val receiverTransform: Array[Float] =
    Array(6.0f, 0f, 0f, 0f, 0f, 0.1f, 0f, -2.0f, 0f, 0f, 6.0f, 0f)

  private def setupMeshReceiverScene(casterMaterial: Material): Unit =
    renderer.clearAllInstances()
    renderer.addSphereInstance(casterTransform, casterMaterial)
    val receiverMaterial = Material(Color(0.9f, 0.9f, 0.9f, 1.0f), ior = 1.0f)
    renderer.addSphereInstance(receiverTransform, receiverMaterial)
    renderer.clearPlanes()
    renderer.setCamera(
      Vector[3](0.0f, 4.0f, 8.0f),
      Vector[3](0.0f, -1.0f, 0.0f),
      Vector[3](0.0f, -1.0f, 0.0f),
      45.0f
    )
    renderer.setLight(Vector[3](0.0f, -1.0f, 0.0f), lightIntensity)

  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  private def meshCausticDelta(casterMaterial: Material): (Double, Double, Double) =
    setupMeshReceiverScene(casterMaterial)
    renderer.enableCaustics(photonsPerIter, causticIterations)
    val onPixels = for
      y <- 0 until imageSize.height
      x <- 0 until imageSize.width
    yield ImageValidation.getRGBAt(renderer.renderWithStats(imageSize).get.image, imageSize, x, y)
    setupMeshReceiverScene(casterMaterial)
    renderer.disableCaustics()
    val offPixels = for
      y <- 0 until imageSize.height
      x <- 0 until imageSize.width
    yield ImageValidation.getRGBAt(renderer.renderWithStats(imageSize).get.image, imageSize, x, y)
    (
      (onPixels.map(_.r).sum - offPixels.map(_.r).sum).toDouble / onPixels.length,
      (onPixels.map(_.g).sum - offPixels.map(_.g).sum).toDouble / onPixels.length,
      (onPixels.map(_.b).sum - offPixels.map(_.b).sum).toDouble / onPixels.length
    )

  behavior of "Diffuse mesh-instance caustic receiver"

  it should "deposit a caustic onto a diffuse receiver mesh instance (no floor plane present)" in:
    if runningUnderSanitizer then cancel("Skipped under compute-sanitizer (too slow)")
    val clearGlass = Material(Color(0.95f, 0.95f, 1.0f, 0.5f), ior = Const.iorGlass)
    val (dr, dg, db) = meshCausticDelta(clearGlass)
    withClue(s"whole-image caustic delta (on-off), no floor plane, diffuse receiver mesh only: " +
      s"R=$dr G=$dg B=$db; must be nonzero — the receiver mesh is the ONLY diffuse surface in " +
      "the scene, so a zero delta means mesh-instance deposit isn't working. ") {
      (dr + dg + db) should be > 0.0
    }

end CausticsMeshReceiverSuite
```

- [ ] **Step 2: Run and confirm it fails against current (Task 1/2, pre-Task-3) code**

```bash
sbt "testOnly io.github.lene.optix.caustics.CausticsMeshReceiverSuite"
```

Expected: FAIL — delta is exactly zero. The scene has zero enabled planes, so `checkPlaneIntersection` never fires, and `__closesthit__photon` refracts through the `ior = 1.0f` receiver (near-transparent pass-through) without depositing anything. If the failure message differs (e.g. a crash or an unrelated error), STOP and investigate before continuing — do not implement against an unverified RED.

- [ ] **Step 3: Implement — `initializeHitPoint` overload for a real surface normal + explicit albedo**

Add directly below the existing `initializeHitPoint` (after `caustics_ppm.cu` Task 1's version, i.e. after the closing `}` of the plane-index version):

```cpp
/**
 * Initialize a hit point at a real geometry hit (mesh/sphere instance), using the actual
 * surface normal and material albedo instead of a plane-derived approximation.
 */
__device__ void initializeHitPoint(
    HitPoint& hp,
    const float3& position,
    const float3& normal,
    const float3& albedo,
    const uint3& idx
) {
    hp.position[0] = position.x;
    hp.position[1] = position.y;
    hp.position[2] = position.z;

    hp.normal[0] = normal.x;
    hp.normal[1] = normal.y;
    hp.normal[2] = normal.z;

    hp.flux[0] = 0.0f;
    hp.flux[1] = 0.0f;
    hp.flux[2] = 0.0f;

    hp.radius = params.caustics.initial_radius;
    hp.n = 0;
    hp.new_photons = 0;

    hp.pixel_x = idx.x;
    hp.pixel_y = idx.y;

    hp.weight[0] = albedo.x;
    hp.weight[1] = albedo.y;
    hp.weight[2] = albedo.z;
}
```

- [ ] **Step 4: Implement — `__raygen__hitpoints` tries the probe before falling back to planes**

In `__raygen__hitpoints`, replace the primary-ray trace block (`:246-260`, the `optixTrace(... RAY_TYPE_PRIMARY ...)` call whose `p0-p3` outputs are unused) with a probe trace using `RAY_TYPE_PHOTON`, then branch on its result before the Task-1 plane-seeding block:

```cpp
    // Probe the camera ray against real geometry first (Phase 2 Task 2/3): reuses the
    // RAY_TYPE_PHOTON hit groups already registered for every geometry type. Only a diffuse
    // instance hit (is_diffuse) allocates a hit point here; a glass-instance hit or a miss
    // falls through to the plane-seeding block below (Task 1), matching pre-Task-3 behavior.
    unsigned int pp0 = 0, pp1 = 0, pp2 = 0, pp3 = 0, pp4 = 0, pp5 = 0,
                 pp6 = 0, pp7 = 0, pp8 = 0, pp9 = 16u;  // bit4 = probe mode
    optixTrace(
        params.handle,
        cam_eye,
        ray_direction,
        HIT_POINT_RAY_TMIN,
        MAX_RAY_DISTANCE,
        0.0f,
        OptixVisibilityMask(255),
        OPTIX_RAY_FLAG_NONE,
        params.sbt_base_offset + SBTConstants::RAY_TYPE_PHOTON,
        SBTConstants::STRIDE_RAY_TYPES,
        SBTConstants::MISS_PHOTON,
        pp0, pp1, pp2, pp3, pp4, pp5, pp6, pp7, pp8, pp9
    );

    const bool probe_hit_diffuse = (pp9 & 1u) != 0u && (pp9 & 2u) != 0u;
    if (probe_hit_diffuse) {
        const float3 hit_pos = make_float3(
            __uint_as_float(pp0), __uint_as_float(pp1), __uint_as_float(pp2));
        const float3 hit_normal = make_float3(
            __uint_as_float(pp3), __uint_as_float(pp4), __uint_as_float(pp5));
        const float3 hit_albedo = make_float3(
            __uint_as_float(pp6), __uint_as_float(pp7), __uint_as_float(pp8));

        const unsigned int hp_idx = atomicAdd(params.caustics.num_hit_points, 1);
        if (hp_idx < MAX_HIT_POINTS) {
            HitPoint& hp = params.caustics.hit_points[hp_idx];
            initializeHitPoint(hp, hit_pos, hit_normal, hit_albedo, idx);
        }
        return;
    }
```

This block replaces the old `p0, p1, p2, p3` trace and sits immediately before the Task 1 plane-seeding loop (which remains unchanged as the fallback for non-diffuse-instance camera rays).

**Regression guard:** for every existing reference scene (glass instances only, `ior = 1.5` or higher, no diffuse instances), `probe_hit_diffuse` is always false (either the probe hits glass — `is_diffuse` bit unset — or misses to background), so execution always falls through to the unchanged Task 1 plane path.

- [ ] **Step 5: Implement — `__closesthit__photon` diffuse-instance deposit branch (real photon transport)**

Insert immediately after Task 2's probe-mode early-return block (so it only runs for real, non-probe photon transport) and before the existing flux-unpack block (`caustics_ppm.cu:931`):

```cpp
    // Phase 2 Task 3: a photon that reaches a DIFFUSE instance (ior <= 1.05) deposits here
    // instead of refracting through it. Preserves the same LS+D gate as the plane path
    // (__miss__photon:1082) — only photons that already touched glass may deposit, so direct
    // illumination on the receiver (already handled by the primary shading pass) isn't
    // double-counted.
    const bool hit_is_diffuse = ior_material <= 1.05f;
    if (hit_is_diffuse) {
        const unsigned int touched_bit_in = optixGetPayload_9() & 4u;
        if (touched_bit_in != 0u) {
            float3 flux_in = make_float3(
                __uint_as_float(optixGetPayload_0()),
                __uint_as_float(optixGetPayload_1()),
                __uint_as_float(optixGetPayload_2())
            );
            depositPhoton(hit_point, ray_dir, flux_in);
            optixSetPayload_9(2u | touched_bit_in);  // alive=false, deposited=true
        } else {
            optixSetPayload_9(touched_bit_in);       // alive=false, deposited=false
        }
        if (params.caustics.stats) {
            atomicAdd(&params.caustics.stats->photons_deposited, 0ULL);  // no-op: depositPhoton already counts
        }
        return;
    }
```

Remove the no-op stats line above (dead code) — it was left in to show the deposit counting already happens inside `depositPhoton` itself (`:395-400`), so no separate stats bump is needed here; delete that `if (params.caustics.stats) { ... }` block entirely from the snippet before committing.

**Regression guard:** every existing reference scene's instances have `ior = 1.5` (glass) or higher — `hit_is_diffuse` is always false, so this branch is never taken and the existing refraction logic below (`:957` onward, unchanged) runs exactly as before.

- [ ] **Step 6: Rebuild native and run the new test**

```bash
sbt "project optixJni" nativeCompile
sbt "testOnly io.github.lene.optix.caustics.CausticsMeshReceiverSuite"
```

Expected: PASS, `withClue` showing a clearly nonzero delta. If it's still zero, apply systematic-debugging (check: is the receiver's top surface actually in the caustic's path — adjust `receiverTransform`'s Y position if needed, keeping the delta-isolation approach so the test stays camera-independent).

- [ ] **Step 7: Run the full existing caustics suite set — confirm zero reference drift**

```bash
sbt "testOnly io.github.lene.optix.caustics.CausticsReferenceSuite"
sbt "testOnly io.github.lene.optix.caustics.CausticsCoverageSuite"
sbt "testOnly io.github.lene.optix.caustics.CausticsWallReceiverSuite"
sbt "testOnly io.github.lene.optix.caustics.MultiObjectCausticsSuite"
sbt "testOnly io.github.lene.optix.caustics.AreaLightCausticsSuite"
```

Expected: all PASS unchanged.

- [ ] **Step 8: Commit**

```bash
git add src/main/native/shaders/caustics_ppm.cu \
    src/test/scala/io/github/lene/optix/caustics/CausticsMeshReceiverSuite.scala
git commit -m "feat(caustics): deposit caustics onto diffuse mesh/sphere instance receivers"
```

---

### Task 4: Coverage-net extension — determinism guard for mesh-receiver deposit

**Repo:** optix-jni

**Files:**
- Modify: `src/test/scala/io/github/lene/optix/caustics/CausticsMeshReceiverSuite.scala`

**Interfaces:**
- Consumes: `setupMeshReceiverScene` from Task 3.

- [ ] **Step 1: Add a determinism assertion above `end CausticsMeshReceiverSuite`**

```scala
  it should "deposit deterministically onto the receiver mesh across identical renders" in:
    if runningUnderSanitizer then cancel("Skipped under compute-sanitizer (too slow)")
    val clearGlass = Material(Color(0.95f, 0.95f, 1.0f, 0.5f), ior = Const.iorGlass)
    setupMeshReceiverScene(clearGlass)
    renderer.enableCaustics(photonsPerIter, causticIterations)
    val statsA = { renderer.renderWithStats(imageSize).get; renderer.getCausticsStats }
    setupMeshReceiverScene(clearGlass)
    renderer.enableCaustics(photonsPerIter, causticIterations)
    val statsB = { renderer.renderWithStats(imageSize).get; renderer.getCausticsStats }
    withClue(s"photonsDeposited A=${statsA.photonsDeposited} B=${statsB.photonsDeposited}; " +
      "mesh-instance deposit must be deterministic across identical renders. ") {
      statsA.photonsDeposited should be > 0L
      statsA.photonsDeposited shouldBe statsB.photonsDeposited
    }
```

- [ ] **Step 2: Run; confirm PASS**

```bash
sbt "testOnly io.github.lene.optix.caustics.CausticsMeshReceiverSuite"
```

Expected: PASS, both runs report identical nonzero `photonsDeposited`. If nondeterministic, STOP — investigate before committing (do not weaken to an approximate comparison).

- [ ] **Step 3: Commit**

```bash
git add src/test/scala/io/github/lene/optix/caustics/CausticsMeshReceiverSuite.scala
git commit -m "test(caustics): lock mesh-receiver deposit determinism (coverage net extension)"
```

---

### Task 5: optix-jni gate + version bump

**Repo:** optix-jni

**Files:**
- Modify: `build.sbt` (`version :=`), `CHANGELOG.md`

- [ ] **Step 1: Bump version and add a CHANGELOG entry**

In `build.sbt`, change `version := "0.1.17"` to `version := "0.1.18"`. Prepend to `CHANGELOG.md`:

```markdown
## [0.1.18] - <today>

### Added

- Caustics deposit onto arbitrary receiver surfaces: all enabled analytic planes (not only
  `planes[0]`), and diffuse mesh/sphere instances (`ior <= 1.05`), via a probe-mode branch in
  the existing photon closest-hit/miss programs (no new ray type or SBT changes).
```

- [ ] **Step 2: Run the full pre-push gate**

```bash
BRANCH=$(git rev-parse --abbrev-ref HEAD); SHA=$(git rev-parse HEAD)
printf 'refs/heads/%s %s refs/heads/%s 0000000000000000000000000000000000000000\n' "$BRANCH" "$SHA" "$BRANCH" | ./.git_hooks/pre-push 2>&1 | tee /tmp/optix-mesh-receivers-gate.log
```

Expected: `Tests: OK`, gtest unchanged, `Scalafix: OK`, `cppcheck: OK`. Fix any failure before proceeding.

- [ ] **Step 3: Commit**

```bash
git add build.sbt CHANGELOG.md
git commit -m "chore: bump optix-jni 0.1.17 -> 0.1.18 (mesh-receiver caustics)"
```

---

### Task 6: Publish 0.1.18 and verify live on Maven Central

**Repo:** optix-jni

**Files:** none (release + verification only).

- [ ] **Step 1: Push, open PR, merge (after explicit user confirmation of the push)**

Standard flow: push the feature branch, `gh pr create`, wait for CI, merge after user confirmation.

- [ ] **Step 2: Tag and trigger publish**

```bash
git tag v0.1.18
git push origin v0.1.18
gh workflow run ci.yml --ref v0.1.18
```

- [ ] **Step 3: Verify all 4 artifacts are live directly against Maven Central**

Do not trust CI job status alone — the Sonatype status-poll has previously false-negatived after a successful upload.

```bash
for suffix in "" "-sources" "-javadoc"; do
  curl -s -o /dev/null -w "%{http_code} optix-jni-0.1.18${suffix}.jar\n" \
    "https://repo1.maven.org/maven2/io/github/lene/optix-jni/0.1.18/optix-jni-0.1.18${suffix}.jar"
done
curl -s -o /dev/null -w "%{http_code} optix-jni-0.1.18.pom\n" \
  "https://repo1.maven.org/maven2/io/github/lene/optix-jni/0.1.18/optix-jni-0.1.18.pom"
```

Expected: all four lines report `200`. If any is not `200` yet, wait and re-check (Central sync can lag minutes) before concluding it failed.

---

### Task 7: menger integration + manual tests

**Repo:** menger

**Files:**
- Modify: `menger/build.sbt` (`optixJniDependency`), `scripts/integration-tests.sh`, `scripts/manual-test.sh`
- Create: `scripts/reference-images/mesh_receiver_caustics.png` (generated)

**Interfaces:**
- Consumes: existing `run_test`, `$CAUSTICS_TIMEOUT` from `integration-tests.sh`; menger CLI object/material/caustics flags as used by existing caustics scenes in that script.

- [ ] **Step 1: Bump menger's optix-jni pin**

In `menger/build.sbt`, change the `optixJniDependency` line from `0.1.16` (or current) to `0.1.18`.

- [ ] **Step 2: Add the integration test function**

After the existing `test_dispersive_caustics()` function in `scripts/integration-tests.sh`:

CLI syntax confirmed against `ObjectType.VALID_TYPES` (`menger-common/src/main/scala/menger/common/ObjectType.scala:5-32`, `cube` is a real triangle-mesh type) and `ObjectSpec`'s own doc examples (`type=sphere:pos=0,0,0:size=1.0:color=#FF0000:ior=1.5`, `type=cube:pos=2,0,0:size=1.5:color=#0000FF` — keys are `pos=`/`size=`, not `position=`/`scale=`/`radius=`). No `material=matte` preset was found in the material-preset registry; the receiver cube is left with no `material=` key so it gets the default diffuse material (`ior` defaults to `1.0f`, well under the `1.05` refractive threshold):

```bash
test_mesh_receiver_caustics() {
    echo "Mesh-receiver caustics (glass sphere caustic on a diffuse cube, no floor plane):"
    TIMEOUT=$CAUSTICS_TIMEOUT run_test "mesh receiver caustics" \
        --objects type=sphere:material=glass:size=1:pos=0,0,0 type=cube:size=6,0.2,6:pos=0,-2,0 \
        --caustics --caustics-photons 1000 --caustics-iterations 1 \
        --light point:0,10,0:500
}
```

Register it in the run-list (edit the block containing `test_colored_glass_caustics` / `test_dispersive_caustics`):

```bash
    test_colored_glass_caustics
    test_dispersive_caustics
    test_mesh_receiver_caustics
```

Before finalizing, confirm against an existing multi-object scene in the same script (e.g. `test_multiobject_caustics`) whether `size=` accepts a non-uniform `x,y,z` triple for `cube` (as written above) or only a single scalar — if only scalar, use `size=6` (a cube, not a flat pad) and instead position it far enough below the caster (`pos=0,-4,0`) that the caustic still projects onto its top face.

- [ ] **Step 3: Confirm initial MISSING-reference failure**

```bash
sbt "project mengerApp" stage
BIN=$(pwd)/menger-app/target/universal/stage/bin/menger-app
PARALLEL_MODE=false ./scripts/integration-tests.sh "$BIN" --filter "mesh receiver caustics"
```

Expected: reports MISSING reference (fail) — proves the test runs and reaches the comparison.

- [ ] **Step 4: Generate the reference**

```bash
PARALLEL_MODE=false ./scripts/integration-tests.sh "$BIN" --update-references --filter "mesh receiver caustics"
git status --short scripts/reference-images/   # must show ONLY the 1 new PNG
```

- [ ] **Step 5: Verify determinism**

```bash
PARALLEL_MODE=false ./scripts/integration-tests.sh "$BIN" --filter "mesh receiver caustics"
```

Expected: `- match (diff: 0%)`. If not deterministic, STOP and investigate before committing the reference.

- [ ] **Step 6: Add the manual scene**

Append to `scripts/manual-test.sh` after the existing `163-diamond-dispersive-caustics` entry:

```bash
run_test "Mesh-receiver caustics (glass sphere casts a caustic onto a diffuse cube, no floor plane)" "-o --objects type=sphere:material=glass:size=1:pos=0,0,0 type=cube:size=6,0.2,6:pos=0,-2,0 --camera-pos 0,4,8 --camera-lookat 0,-1,0 --light point:0,10,0:500 --caustics --caustics-photons 500000 --caustics-iterations 20 -s $OUTPUT_DIR/164-mesh-receiver-caustics.png"
```

- [ ] **Step 7: Smoke-run the manual scene**

```bash
__GL_THREADED_OPTIMIZATIONS=0 xvfb-run -a "$BIN" --headless \
    --objects type=sphere:material=glass:size=1:pos=0,0,0 type=cube:size=6,0.2,6:pos=0,-2,0 \
    --camera-pos 0,4,8 --camera-lookat 0,-1,0 --light point:0,10,0:500 \
    --caustics --caustics-photons 200000 --caustics-iterations 8 --save-name /tmp/164-mesh-receiver.png
```

Expected: `/tmp/164-mesh-receiver.png` written; open it and confirm a visible caustic on the cube's top face.

- [ ] **Step 8: Commit**

```bash
git add menger/build.sbt scripts/integration-tests.sh scripts/manual-test.sh \
    scripts/reference-images/mesh_receiver_caustics.png
git commit -m "test(caustics): integration + manual scene for mesh-receiver caustics (optix-jni 0.1.18)"
```

---

### Task 8: menger version bump + DoD gate + push

**Repo:** menger

**Files:**
- Modify: `menger-app/build.sbt`, `.gitlab-ci.yml`, `menger-app/src/main/scala/menger/MengerCLIOptions.scala`, `docs/guide/user-guide.md`, `docs/USER_GUIDE.md`, `CHANGELOG.md`

**Interfaces:** none.

- [ ] **Step 1: Ask the user for the next menger version number**

Do not infer it (standing constraint). The prior version in this roadmap was 0.8.3; confirm the next number with the user before editing any file.

- [ ] **Step 2: Bump the version across all five files and add a CHANGELOG section**

Replace the old version string with the confirmed new one in: `menger-app/build.sbt` (`version :=`), `.gitlab-ci.yml` (`DEPLOYABLE_VERSION`), `MengerCLIOptions.scala` (`version("menger v... ")`), `docs/guide/user-guide.md` (`**Version**:`), `docs/USER_GUIDE.md` (`**Version**:`). Prepend to `CHANGELOG.md`:

```markdown
## [<new version>] - <today>

### Added

- Caustics now deposit on arbitrary receiver surfaces: any enabled plane, and diffuse
  mesh/sphere instances — not only the floor plane. Consumes optix-jni 0.1.18.
```

- [ ] **Step 3: Run the full DoD gate**

```bash
LOCAL=$(git rev-parse HEAD)
printf 'refs/heads/feature/caustics-mesh-receivers %s refs/heads/feature/caustics-mesh-receivers <REMOTE_SHA>\n' "$LOCAL" | ./.git_hooks/pre-push 2>&1 | tee /tmp/menger-mesh-receivers-gate.log
```

(Use the current `origin/feature/...` SHA as `<REMOTE_SHA>`, or the merge-base with `origin/main` for a fresh branch.) Expected: `Passed: N/N` integration (including the new scene), coverage ≥ 80%, Valgrind/cppcheck/clang-tidy pass, `MENGER_PREPUSH_RC=0`. Fix any failure in this same branch before pushing.

- [ ] **Step 4: Commit**

```bash
git add menger-app/build.sbt .gitlab-ci.yml \
    menger-app/src/main/scala/menger/MengerCLIOptions.scala \
    docs/guide/user-guide.md docs/USER_GUIDE.md CHANGELOG.md
git commit -m "chore: bump menger version (mesh-receiver caustics, optix-jni 0.1.18)"
```

- [ ] **Step 5: Push (explicit user confirmation required)**

Await explicit "push" confirmation from the user before pushing. Monitor the resulting GitLab pipeline to completion; fix any failure.

---

## Notes for the executor

- Tasks 1-4 are TDD in the normal sense (RED first, confirmed failing for the right reason, then GREEN). Tasks 5-8 are release/integration mechanics with no new production logic — their "test" is the gate/pipeline itself.
- The probe-mode design in Task 2 is the load-bearing decision of this plan: it deliberately reuses the already-registered `RAY_TYPE_PHOTON` SBT hit groups (one per geometry type) instead of adding a new ray type, which would require touching `PipelineManager.cpp`'s program-group/SBT-stride construction (`STRIDE_RAY_TYPES`, hit-group record layout) — confirmed via direct inspection to be substantial, high-risk surgery on the two highest-churn files in the repo (`OptiXWrapper.cpp` 99.2%ile, `PipelineManager.cpp`). Do not "simplify" Task 2 into a new ray type without discussing the tradeoff with the user first.
- `numPayloadValues = 11` (`OptiXContext.cpp:354,480`, `PipelineManager.cpp:22`) is a pipeline-wide ceiling already fully used by both the primary path (RGB+depth+denoise+wavelength) and real photon transport (flux+origin+dir+flags+wavelength). The probe-mode payload layout in Task 2 uses a fresh, independent 10-register set for its own `optixTrace` call — it does not collide with either of those, since payload registers are scoped per `optixTrace` invocation, not global.
- Push order once all gates are green: optix-jni branch → PR → merge → tag → publish → verify Maven Central (Tasks 5-6) → **then** menger branch → push (Tasks 7-8). Await explicit user confirmation before any push, matching this session's established pattern.
- Task 7's `type=cube`/`pos=`/`size=` CLI flags are confirmed against `ObjectType.VALID_TYPES` and `ObjectSpec`'s own doc examples; the one open question is whether `size=` accepts a non-uniform `x,y,z` triple for `cube` (Step 2's fallback note covers this) — verify against `test_multiobject_caustics` before finalizing.
