# Temporal Stability, OptiX Temporal Denoiser (Phase 4) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Kill animation flicker in caustics-enabled scenes by switching the OptiX denoiser from spatial-only HDR to the temporal model, fed by per-pixel motion vectors that account for **both camera and object motion** (user's explicit scope choice).

**Architecture:** `DenoiserManager`'s `denoiser_` already outlives a single `render()` call (owned by `OptiXWrapper::Impl::denoiser_manager`, a `unique_ptr` never reset except in `dispose()`/`releaseDenoiser()`) — Phase 4 extends this object in place, no new ownership. Cross-frame object-instance identity is tracked **natively, not in Scala**: `Impl::instances` is already an index-aligned `std::vector<ObjectInstance>` repopulated every frame in stable order (menger's DSL scene function returns the same object list shape frame-to-frame for any animation); a new `Impl::previous_instances` snapshot plus a per-instance delta-transform computation gives the GPU a per-instance "where did this exact material point move" answer, with a topology-mismatch fallback (identity motion) so a frame where object count changes never crashes, just degrades for one frame. Per-pixel screen-space flow is computed **on the GPU**, by reprojecting each pixel's actual world-space hit point through the instance's motion delta and the *previous frame's* camera basis — inverting this ray tracer's existing pinhole camera-ray formula (`generateCameraRay`, `caustics_ppm.cu:196-213`) via a closed-form 3×3 basis solve, not a rasterizer-style view-projection matrix (none exists in this codebase, and none is needed).

**Tech Stack:** CUDA/OptiX (`.cu` shaders, `OptixDenoiserModelKind`/`OptixDenoiserGuideLayer`/`OptixDenoiserLayer` from `optix_types.h`), C++ (`OptiXWrapper.cpp`, `DenoiserManager.cpp`), Scala 3 ScalaTest, menger's `WithAnimation`/`DenoiseMode`/`BaseEngine`.

## Global Constraints

- Scala 3 only; no `var`/`while`/`asInstanceOf`/`throw` in production. Max line length 100.
- C++/CUDA: manual edits only — no `sed`/scripts for bulk edits. Match existing style (Doxygen `/** */` on new device/host functions).
- **RNG/regression discipline (non-negotiable):** all new state/logic is gated on `denoise_mode == TEMPORAL` (menger: `DenoiseMode.Temporal`); `Off`/`Final` paths and every still-image reference execute zero new code. `clearAllInstances()` (`OptiXWrapper.cpp:3374-3562`) does **not** touch `denoiser_manager` or any new temporal-history state — confirmed by direct read, so switching modes mid-run doesn't require new cleanup there.
- Every rendering feature ships BOTH a headless integration test AND a manual scene (menger policy).
- One optix-jni release per phase (0.1.20): tests green → pre-push gate → publish → verify all 4 Maven Central artifacts → bump menger pin → menger DoD gate green.
- Never commit to main; never push without explicit user confirmation; never `git add -A`; every commit trailer includes `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>` and `Claude-Session: https://claude.ai/code/session_0128FY5RY2p5AZorgHML8u6G`.
- OptiX SDK API surface used (verified against `/usr/local/optix/include/optix_types.h` and `optix_host.h` on this machine, not assumed):
  - `OPTIX_DENOISER_MODEL_KIND_TEMPORAL = 0x2325` (the non-AOV temporal model — `OptixDenoiserGuideLayer::flow`/`OptixDenoiserLayer::previousOutput` are the only new fields needed; `previousOutputInternalGuideLayer`/`outputInternalGuideLayer` are AOV-only, not used here).
  - `OptixDenoiserParams::temporalModeUsePreviousLayers` (`unsigned int`, must be `0` on the first frame of a sequence, `1` thereafter).
  - `optixDenoiserInvoke(denoiser, stream, params, denoiserState, denoiserStateSizeInBytes, guideLayer, layers, numLayers, inputOffsetX, inputOffsetY, scratch, scratchSizeInBytes)` — the two `0`s in the existing call (`DenoiserManager.cpp:99-100`) are `inputOffsetX`/`inputOffsetY`, confirmed from the header, not a layer index.

---

### Task 1: Cross-frame instance-transform + camera-history tracking

**Repo:** optix-jni

**Files:**
- Modify: `src/main/native/OptiXWrapper.cpp` (`Impl` struct at `:42-228`, `render()` at `:1326+`)
- Modify: `src/main/native/include/OptiXWrapper.h` (new public method declaration, near `:148` `setDenoisingEnabled`)
- Modify: `src/main/native/JNIBindings.cpp` (new JNI binding, mirroring `:381-390`)
- Modify: `src/main/scala/io/github/lene/optix/OptiXRenderer.scala` (new `@native` declaration, near `:284` `setDenoisingEnabledNative`)
- Create: `src/test/scala/io/github/lene/optix/TemporalMotionSuite.scala`

**Interfaces:**
- Produces: `Impl` gains `std::vector<ObjectInstance> previous_instances`, `bool has_previous_frame = false`, and a snapshot of the previous frame's `SceneParameters::CameraParams` (`float previous_eye[3]`, `previous_u[3]`, `previous_v[3]`, `previous_w[3]`). A new host function `computeInstanceMotionDelta(const ObjectInstance& prev, const ObjectInstance& curr, float out_delta[12])` computing a 4×3 affine delta transform. A new public `OptiXWrapper::getInstanceMotionDelta(int instanceId, float* out12) -> bool` (returns `false`/identity when no previous frame or topology mismatch for that instance) — JNI-exposed as `getInstanceMotionDeltaNative(instanceId: Int): Array[Float]` for testability.
- Consumes: `Impl::instances` (existing, `OptiXWrapper.cpp:113`, `std::vector<ObjectInstance>`, `transform[12]` 4×3 row-major), `impl->scene.getCamera()` (existing, `SceneParameters.h:60-61`, returns `CameraParams{eye[3], u[3], v[3], w[3], fov, dirty}`).

**Algorithm (exact, use this — do not redesign):**

**Corrected during Task 1's execution** (verified against this file's own instance-center
extraction at `:1872-1874`, which reads `transform[3]/[7]/[11]` as `(cx, cy, cz)` — the standard
OptiX `OptixInstance::transform` convention): a `transform[12]` is a row-major **3×4** affine
matrix — 3 rows of 4, where the first 3 entries of each row are that row's linear part and the
4th is that row's translation component: `transform[0..2]`=row0 linear + `transform[3]`=tx,
`transform[4..6]`=row1 linear + `transform[7]`=ty, `transform[8..10]`=row2 linear +
`transform[11]`=tz. (An earlier draft of this plan incorrectly described this as "9 linear
floats then 3 translation floats appended at the end" — that layout does NOT match this
codebase; use `extractLinearAndTranslation`/`packLinearAndTranslation` helpers, added during
Task 1, to convert between the packed 3×4 form and a plain 3×3 matrix + 3-vector before doing
the actual matrix math below.) `world_point = M * local_point + t`. The **delta** that maps a
point that was at `curr_world` this frame to where that same material point on the object was
**last frame** is:

```
delta_world_point = prev_transform(curr_transform^-1(curr_world_point))
```

i.e. un-transform by the current frame's matrix (recover the local-space point) then re-transform by the previous frame's matrix. Composed as a single affine map: `delta_M = prev_M * curr_M^-1`, `delta_t = prev_t - delta_M * curr_t`. Implement via a standard 3×3 matrix inverse (cofactor/adjugate method — determinant + 9 cofactors, standard closed form, do not use an iterative solver) applied to `curr_M`, then two 3×3-matrix-times-vector/matrix multiplies.

- [ ] **Step 1: Write the failing test — motion delta reflects a real transform change, and falls back to identity on topology mismatch**

Create `src/test/scala/io/github/lene/optix/TemporalMotionSuite.scala`:

```scala
package io.github.lene.optix

import menger.common.Color
import menger.common.ImageSize
import menger.common.Material
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Phase 4 Task 1: native cross-frame instance-transform tracking. `getInstanceMotionDelta`
  * returns identity (no motion) on the very first frame (nothing to compare against) and on any
  * topology mismatch (instance count changed since the last frame) — both are safe, non-crashing
  * fallbacks. Once a second frame renders with the SAME topology but a moved instance, the delta
  * must reflect the actual applied transform change.
  */
class TemporalMotionSuite extends AnyFlatSpec with Matchers with RendererFixture:

  private val runningUnderSanitizer: Boolean =
    sys.env.get("RUNNING_UNDER_COMPUTE_SANITIZER").contains("true")

  private val imageSize: ImageSize = ImageSize(64, 64)

  private def identityTransform: Array[Float] =
    Array(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f)

  private def translatedTransform(dx: Float): Array[Float] =
    Array(1f, 0f, 0f, dx, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f)

  private val whiteMatte: Material = Material(Color(0.8f, 0.8f, 0.8f, 1.0f))

  behavior of "Cross-frame instance motion tracking"

  it should "report identity motion on the first frame (nothing to compare against)" in:
    if runningUnderSanitizer then cancel("Skipped under compute-sanitizer (too slow)")
    renderer.clearAllInstances()
    val id = renderer.addSphereInstance(identityTransform, whiteMatte)
    renderer.setDenoisingEnabled(true)
    renderer.setTemporalDenoisingEnabled(true)
    renderer.renderWithStats(imageSize).get
    val delta = renderer.getInstanceMotionDelta(id)
    delta shouldBe identityTransform

  it should "report the real delta when an instance moves between two temporal-mode frames" in:
    if runningUnderSanitizer then cancel("Skipped under compute-sanitizer (too slow)")
    renderer.clearAllInstances()
    val id1 = renderer.addSphereInstance(identityTransform, whiteMatte)
    renderer.setDenoisingEnabled(true)
    renderer.setTemporalDenoisingEnabled(true)
    renderer.renderWithStats(imageSize).get
    renderer.clearAllInstances()
    val id2 = renderer.addSphereInstance(translatedTransform(2.0f), whiteMatte)
    renderer.renderWithStats(imageSize).get
    id2 shouldBe id1 // same positional index -> same identity, per this design
    val delta = renderer.getInstanceMotionDelta(id2)
    // delta must map the CURRENT (translated) point back toward the PREVIOUS (origin) point:
    // delta_t.x should be approximately -2.0 (undo the +2.0 shift).
    delta(9) should be(-2.0f +- 0.01f)

  it should "fall back to identity motion when instance topology changes (no crash)" in:
    if runningUnderSanitizer then cancel("Skipped under compute-sanitizer (too slow)")
    renderer.clearAllInstances()
    val id1 = renderer.addSphereInstance(identityTransform, whiteMatte)
    renderer.setDenoisingEnabled(true)
    renderer.setTemporalDenoisingEnabled(true)
    renderer.renderWithStats(imageSize).get
    renderer.clearAllInstances()
    val idA = renderer.addSphereInstance(identityTransform, whiteMatte)
    val idB = renderer.addSphereInstance(translatedTransform(1.0f), whiteMatte) // topology changed: 1 -> 2 instances
    noException should be thrownBy renderer.renderWithStats(imageSize).get
    renderer.getInstanceMotionDelta(idA) shouldBe identityTransform
    renderer.getInstanceMotionDelta(idB) shouldBe identityTransform

end TemporalMotionSuite
```

- [ ] **Step 2: Run and confirm it fails against current code**

```bash
sbt "testOnly io.github.lene.optix.TemporalMotionSuite"
```

Expected: compile failure (`setTemporalDenoisingEnabled`/`getInstanceMotionDelta` don't exist yet). This is the correct RED for a new-API task — confirm the compiler error names exactly these missing methods, not something else.

- [ ] **Step 3: Implement — `Impl` additions and camera-history snapshot**

In `OptiXWrapper.cpp`, add to `Impl` (after `bool denoising_enabled = false;` at line 55):

```cpp
    // Phase 4: cross-frame temporal state, used only when denoising_enabled + temporal_denoising_enabled.
    bool temporal_denoising_enabled = false;
    std::vector<ObjectInstance> previous_instances;
    bool has_previous_frame = false;
    float previous_cam_eye[3] = {0.0f, 0.0f, 0.0f};
    float previous_cam_u[3] = {0.0f, 0.0f, 0.0f};
    float previous_cam_v[3] = {0.0f, 0.0f, 0.0f};
    float previous_cam_w[3] = {0.0f, 0.0f, 0.0f};
```

(Note: `ObjectInstance` is declared above this point in the same struct, `:87-111` — the new members go after it's fully defined, i.e. after the `std::vector<ObjectInstance> instances;` line `:113`, not before. Place them there instead if `ObjectInstance` isn't yet in scope at line 55 — verify by compiling.)

- [ ] **Step 4: Implement — `computeInstanceMotionDelta` (3×3 affine inverse + compose)**

Add near the top of `OptiXWrapper.cpp` (file-local, anonymous namespace or `static`), before its first use:

```cpp
namespace {

// Inverts a 3x3 row-major matrix via the cofactor/adjugate method. Returns false (leaves `out`
// unmodified) if the matrix is singular (determinant ~0) -- callers must fall back to identity.
bool invert3x3(const float m[9], float out[9]) {
    const float det =
        m[0] * (m[4] * m[8] - m[5] * m[7]) -
        m[1] * (m[3] * m[8] - m[5] * m[6]) +
        m[2] * (m[3] * m[7] - m[4] * m[6]);
    if (std::fabs(det) < 1e-8f) return false;
    const float inv_det = 1.0f / det;
    out[0] = (m[4] * m[8] - m[5] * m[7]) * inv_det;
    out[1] = (m[2] * m[7] - m[1] * m[8]) * inv_det;
    out[2] = (m[1] * m[5] - m[2] * m[4]) * inv_det;
    out[3] = (m[5] * m[6] - m[3] * m[8]) * inv_det;
    out[4] = (m[0] * m[8] - m[2] * m[6]) * inv_det;
    out[5] = (m[2] * m[3] - m[0] * m[5]) * inv_det;
    out[6] = (m[3] * m[7] - m[4] * m[6]) * inv_det;
    out[7] = (m[1] * m[6] - m[0] * m[7]) * inv_det;
    out[8] = (m[0] * m[4] - m[1] * m[3]) * inv_det;
    return true;
}

void mat3MulMat3(const float a[9], const float b[9], float out[9]) {
    for (int r = 0; r < 3; ++r) {
        for (int c = 0; c < 3; ++c) {
            out[r * 3 + c] = a[r * 3 + 0] * b[0 * 3 + c]
                            + a[r * 3 + 1] * b[1 * 3 + c]
                            + a[r * 3 + 2] * b[2 * 3 + c];
        }
    }
}

void mat3MulVec3(const float m[9], const float v[3], float out[3]) {
    for (int r = 0; r < 3; ++r) {
        out[r] = m[r * 3 + 0] * v[0] + m[r * 3 + 1] * v[1] + m[r * 3 + 2] * v[2];
    }
}

// Computes delta such that delta_transform(curr_world_point) ~= where that same material point
// was last frame. transform[] layout: [0..8] = 3x3 row-major linear part, [9..11] = translation.
// Returns false (identity) if curr's linear part is singular.
bool computeInstanceMotionDelta(const float prev_transform[12], const float curr_transform[12],
                                 float out_delta[12]) {
    float curr_inv[9];
    if (!invert3x3(curr_transform, curr_inv)) return false;

    float delta_m[9];
    mat3MulMat3(prev_transform, curr_inv, delta_m);
    for (int i = 0; i < 9; ++i) out_delta[i] = delta_m[i];

    float delta_m_curr_t[3];
    mat3MulVec3(delta_m, &curr_transform[9], delta_m_curr_t);
    out_delta[9]  = prev_transform[9]  - delta_m_curr_t[0];
    out_delta[10] = prev_transform[10] - delta_m_curr_t[1];
    out_delta[11] = prev_transform[11] - delta_m_curr_t[2];
    return true;
}

} // namespace
```

- [ ] **Step 5: Implement — snapshot at end of `render()`, camera-history capture**

In `render()`, after the existing tone-map/output block finishes (after the block ending around `OptiXWrapper.cpp:1980`, i.e. right before `render()`'s closing `}` — locate the exact end by reading forward from line 1980 to the function's closing brace), add:

```cpp
        // Phase 4: snapshot this frame's instances + camera as "previous" for the next frame's
        // temporal-denoiser motion-vector computation. Gated on temporal_denoising_enabled so
        // Off/Final paths never pay this cost or retain this state.
        if (impl->temporal_denoising_enabled) {
            impl->previous_instances = impl->instances;
            const auto& cam = impl->scene.getCamera();
            std::memcpy(impl->previous_cam_eye, cam.eye, 3 * sizeof(float));
            std::memcpy(impl->previous_cam_u, cam.u, 3 * sizeof(float));
            std::memcpy(impl->previous_cam_v, cam.v, 3 * sizeof(float));
            std::memcpy(impl->previous_cam_w, cam.w, 3 * sizeof(float));
            impl->has_previous_frame = true;
        }
```

**Regression guard:** gated on `temporal_denoising_enabled` (default `false`, only settable via the new method added in Step 7) — `Off`/`Final` modes never execute this block, `previous_instances` stays empty, zero extra copy cost.

- [ ] **Step 6: Implement — `getInstanceMotionDelta` public method**

In `OptiXWrapper.h`, add near `setDenoisingEnabled` (`:148`):

```cpp
// Phase 4: returns the affine delta transform (12 floats, same 4x3 layout as instance
// transforms) mapping this frame's world-space points on `instanceId` to where that same
// material point was last frame. Returns identity (and writes it to out12) when there is no
// previous frame yet, the instance count/geometry-type doesn't match the previous frame
// (topology change), or instanceId is out of range -- never throws, never crashes.
void getInstanceMotionDelta(int instanceId, float* out12) const;
```

In `OptiXWrapper.cpp`, implement (near `clearAllInstances`, e.g. directly after it):

```cpp
void OptiXWrapper::getInstanceMotionDelta(int instanceId, float* out12) const {
    const float identity[12] = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0};
    std::memcpy(out12, identity, 12 * sizeof(float));

    if (!impl->has_previous_frame) return;
    if (instanceId < 0 || static_cast<size_t>(instanceId) >= impl->instances.size()) return;
    if (impl->previous_instances.size() != impl->instances.size()) return;  // topology mismatch

    const auto& curr = impl->instances[static_cast<size_t>(instanceId)];
    const auto& prev = impl->previous_instances[static_cast<size_t>(instanceId)];
    if (prev.geometry_type != curr.geometry_type) return;  // topology mismatch, this slot

    float delta[12];
    if (computeInstanceMotionDelta(prev.transform, curr.transform, delta)) {
        std::memcpy(out12, delta, 12 * sizeof(float));
    }
}
```

Also add the enable/disable setter mirroring `setDenoisingEnabled`'s pattern (`OptiXWrapper.h` near `:148`, `OptiXWrapper.cpp` near the existing `setDenoisingEnabled` implementation):

```cpp
void setTemporalDenoisingEnabled(bool enabled);  // .h
```
```cpp
void OptiXWrapper::setTemporalDenoisingEnabled(bool enabled) {
    impl->temporal_denoising_enabled = enabled;
    if (!enabled) {
        impl->has_previous_frame = false;  // force a fresh start if re-enabled later
        impl->previous_instances.clear();
    }
}
```

- [ ] **Step 7: Implement — JNI binding + Scala wrapper**

In `JNIBindings.cpp`, add after `Java_io_github_lene_optix_OptiXRenderer_setDenoisingEnabledNative` (`:381-390`):

```cpp
JNIEXPORT void JNICALL Java_io_github_lene_optix_OptiXRenderer_setTemporalDenoisingEnabledNative(
    JNIEnv* env, jobject obj, jboolean enabled) {
    try {
        OptiXWrapper* wrapper = getWrapper(env, obj);
        if (wrapper != nullptr)
            wrapper->setTemporalDenoisingEnabled(enabled == JNI_TRUE);
    } catch (const std::exception& e) {
        std::cerr << "[JNI] Error in setTemporalDenoisingEnabledNative: " << e.what() << std::endl;
    }
}

JNIEXPORT jfloatArray JNICALL Java_io_github_lene_optix_OptiXRenderer_getInstanceMotionDeltaNative(
    JNIEnv* env, jobject obj, jint instanceId) {
    try {
        OptiXWrapper* wrapper = getWrapper(env, obj);
        jfloatArray result = env->NewFloatArray(12);
        if (wrapper != nullptr && result != nullptr) {
            float delta[12];
            wrapper->getInstanceMotionDelta(static_cast<int>(instanceId), delta);
            env->SetFloatArrayRegion(result, 0, 12, delta);
        }
        return result;
    } catch (const std::exception& e) {
        std::cerr << "[JNI] Error in getInstanceMotionDeltaNative: " << e.what() << std::endl;
        return env->NewFloatArray(12);
    }
}
```

In `OptiXRenderer.scala`, add near `setDenoisingEnabledNative` (`:284-291`), following the exact `<name>Native` + public-wrapper pattern used there:

```scala
  @native private def setTemporalDenoisingEnabledNative(enabled: Boolean): Unit

  /** Enables or disables camera+object motion-vector tracking for the OptiX temporal
    * denoiser. Has no effect unless [[setDenoisingEnabled]] is also on. Safe to call at any
    * time -- silently no-ops when the renderer is not initialized. */
  def setTemporalDenoisingEnabled(enabled: Boolean): Unit =
    if isInitialized then setTemporalDenoisingEnabledNative(enabled)

  @native private def getInstanceMotionDeltaNative(instanceId: Int): Array[Float]

  /** Returns the 12-float (4x3 row-major) affine delta transform mapping this frame's
    * world-space points on `instanceId` to where that same point was last frame. Identity
    * when there is no previous temporal frame or the instance topology changed. */
  def getInstanceMotionDelta(instanceId: Int): Array[Float] = getInstanceMotionDeltaNative(instanceId)
```

- [ ] **Step 8: Rebuild native, run the new test**

```bash
sbt "project optixJni" nativeCompile
sbt "testOnly io.github.lene.optix.TemporalMotionSuite"
```

Expected: PASS on all 3 tests. If the delta test fails numerically, apply systematic-debugging on `computeInstanceMotionDelta` first (verify the 3×3 inverse against a hand-computed example — the identity-linear-part case with only a translation change, exercised by this test, should reduce to `delta_m = identity`, `delta_t = prev_t - curr_t`, i.e. `-2.0` exactly for this test's inputs).

- [ ] **Step 9: Run full existing test suite for zero regression**

```bash
sbt "project optixJni" nativeTest
sbt test
```

Expected: all PASS unchanged — new fields are additive, all new logic gated on `temporal_denoising_enabled` (default `false`).

- [ ] **Step 10: Commit**

```bash
git add src/main/native/OptiXWrapper.cpp src/main/native/include/OptiXWrapper.h \
    src/main/native/JNIBindings.cpp src/main/scala/io/github/lene/optix/OptiXRenderer.scala \
    src/test/scala/io/github/lene/optix/TemporalMotionSuite.scala
git commit -m "feat(temporal): track cross-frame instance-transform + camera history natively"
```

---

### Task 2: GPU per-pixel flow-vector computation

**Repo:** optix-jni

**Files:**
- Modify: `src/main/native/shaders/helpers.cu` (`writeDenoiseGuides`, `:1574` — this is the single correct insertion point, verified this session, see below)
- Modify: all 9 geometry closest-hit shaders' `writeDenoiseGuides(...)` call sites (one-line signature extension each): `hit_sphere.cu:63`, `hit_cone.cu:189`, `hit_curve.cu:81`, `hit_cylinder.cu:272`, `hit_hexadecachoron4d.cu:276`, `hit_menger4d.cu:359`, `hit_plane.cu:147`, `hit_sierpinski4d.cu:248`, `hit_triangle.cu:312`
- Modify: `src/main/native/include/OptiXData.h` (new `params.instance_motion` device pointer field, new `flow` output buffer pointer field on `BaseParams` or equivalent)
- Modify: `src/main/native/BufferManager.cpp`/`.h` (new flow-buffer allocation, mirroring `ensureDenoiseBuffers`)
- Modify: `src/main/native/OptiXWrapper.cpp` (upload `instance_motion` array before launch, wire flow buffer pointer into `params`, gated on `temporal_denoising_enabled`)

**Why `writeDenoiseGuides` and not a raygen (verified this session, do not redesign):** the primary ray's `optixTrace` call (`raygen_primary.cu:85-98`) already carries exactly 11 payload values (`r,g,b,p3=depth,albedo_r/g/b,normal_x/y/z,p10=hero_lambda`) — the pipeline's fixed `numPayloadValues` ceiling, with zero free slots to also carry `hit_point`/instance-ID back to the raygen for a flow-vector write there. Reflection/refraction is also recursive with multiple exit points per geometry file (`handleFullyTransparent`/`handleMetallicOpaque`/`handleFullyOpaque`/`traceFinalNonRecursiveRay`/`blendFresnelColorsAndSetPayload` in `hit_sphere.cu` alone), so there is no single shared "final color" call site either. `writeDenoiseGuides(albedo, world_normal)` (`helpers.cu:1574`), however, is called exactly once per geometry type, at an equivalent point in all 9 `hit_*.cu` files (confirmed by grep), BEFORE any bounce recursion begins, and it already opens with `if (!params.write_denoise_guides || optixGetPayload_3() != 0u) return;` — i.e. it already gates on "primary ray, depth 0 only," which is exactly the semantics flow-vector computation needs (G-buffer-style: track the FIRST surface a camera ray hits, not deep multi-bounce reflection/refraction content). `optixGetInstanceId()` and `optixGetLaunchIndex()` are both callable from anywhere inside an active OptiX program (standard device intrinsics, no payload needed) — only `hit_point` needs to be passed in, since it's a local variable at each call site (confirmed present, computed before `writeDenoiseGuides`, in all 9 files).

**Interfaces:**
- Consumes: Task 1's `computeInstanceMotionDelta`-populated `params.instance_motion` device array (uploaded once per frame by `OptiXWrapper::render()`, not recomputed per-pixel), `params.previous_cam_eye/u/v/w` (uploaded from Task 1's `Impl::previous_cam_*`, NOT `pending_cam_*` — the promoted, one-frame-behind values).
- Produces: a new `__device__ float2 reprojectToPreviousFrame(const float3& world_point, const float3& prev_eye, const float3& prev_u, const float3& prev_v, const float3& prev_w, int width, int height)` device function in `helpers.cu`; `writeDenoiseGuides` gains a third parameter `const float3& hit_point` and, when `params.instance_motion`/`params.flow` are non-null, writes `params.flow[pixel_idx]` (a `float2*` device buffer, one entry per pixel) for the primary hit only.

**Algorithm (exact — this inverts `generateCameraRay`'s formula, `caustics_ppm.cu:196-213`, do not redesign):**

`generateCameraRay` computes `ray_dir = normalize(u_ndc*cam_u + v_ndc*cam_v + cam_w)` where `u_ndc, v_ndc` are the pixel's NDC coordinates. To find which *previous-frame* pixel a given world point `P` would have projected to: solve `(P - prev_eye) = t*(u_ndc*prev_u + v_ndc*prev_v + prev_w)` for `(u_ndc, v_ndc)` via a 3×3 basis solve (Cramer's rule via cross products — `prev_u, prev_v, prev_w` are linearly independent by construction, being a camera basis):

```cpp
/**
 * Solve dir = a*u + b*v + c*w for (a,b,c) via Cramer's rule (cross-product 3x3 solve).
 */
__device__ float3 solveBasisCoefficients(const float3& dir, const float3& u, const float3& v, const float3& w) {
    const float3 cvw = cross(v, w);
    const float det = dot(u, cvw);
    if (fabsf(det) < 1e-8f) return make_float3(0.0f, 0.0f, 0.0f);
    const float3 cwu = cross(w, u);
    const float3 cuv = cross(u, v);
    return make_float3(dot(dir, cvw) / det, dot(dir, cwu) / det, dot(dir, cuv) / det);
}

/**
 * Reproject a world-space point into the PREVIOUS frame's pixel coordinates, by inverting
 * generateCameraRay's u_ndc*cam_u + v_ndc*cam_v + cam_w formula (caustics_ppm.cu:212). Returns
 * (-1,-1) if the point is behind the previous camera or the basis is degenerate (caller must
 * treat this as "no valid flow" and skip writing to the flow buffer for that pixel).
 */
__device__ float2 reprojectToPreviousFrame(
    const float3& world_point, const float3& prev_eye,
    const float3& prev_u, const float3& prev_v, const float3& prev_w,
    int width, int height
) {
    const float3 dir = world_point - prev_eye;
    const float3 coeffs = solveBasisCoefficients(dir, prev_u, prev_v, prev_w);
    if (coeffs.z < 1e-6f) return make_float2(-1.0f, -1.0f);
    const float u_ndc = coeffs.x / coeffs.z;
    const float v_ndc = coeffs.y / coeffs.z;
    const float px = (u_ndc + 1.0f) * 0.5f * static_cast<float>(width) - 0.5f;
    const float py = (1.0f - v_ndc) * 0.5f * static_cast<float>(height) - 0.5f;
    return make_float2(px, py);
}
```

`writeDenoiseGuides`'s new signature and body (`helpers.cu:1574`, replace the existing function — the guard changes from a single early-return to two independently-gated sections, since flow must be written even on frames where `write_denoise_guides` is false, e.g. every frame of a temporal-mode animation, not just accumulation-frame 0):

```cpp
__device__ void writeDenoiseGuides(const float4& albedo, const float3& world_normal, const float3& hit_point) {
    if (optixGetPayload_3() != 0u) {
        return;  // not the primary (depth-0) hit -- flow and guides are both primary-only.
    }

    if (params.write_denoise_guides) {
        const float3 camera_u = normalize(make_float3(
            params.camera_u[0], params.camera_u[1], params.camera_u[2]));
        const float3 camera_v = normalize(make_float3(
            params.camera_v[0], params.camera_v[1], params.camera_v[2]));
        const float3 camera_w = normalize(make_float3(
            params.camera_w[0], params.camera_w[1], params.camera_w[2]));
        const float3 normal = normalize(world_normal);
        const float3 camera_normal = make_float3(
            dot(normal, camera_u), dot(normal, camera_v), dot(normal, camera_w));
        // ... existing body below this point is UNCHANGED, just re-indented under this if ...
    }

    if (params.instance_motion != nullptr && params.flow != nullptr) {
        const unsigned int inst_id = optixGetInstanceId();
        const float* delta = &params.instance_motion[inst_id * 12];
        // delta[] is row-major 3x4 (OptiX transform convention, see Task 1's algorithm note):
        // row0=[delta[0..2], tx=delta[3]], row1=[delta[4..6], ty=delta[7]],
        // row2=[delta[8..10], tz=delta[11]]. Apply the instance's motion delta to get where
        // this material point was last frame.
        const float3 prev_local = make_float3(
            delta[0]*hit_point.x + delta[1]*hit_point.y + delta[2]*hit_point.z + delta[3],
            delta[4]*hit_point.x + delta[5]*hit_point.y + delta[6]*hit_point.z + delta[7],
            delta[8]*hit_point.x + delta[9]*hit_point.y + delta[10]*hit_point.z + delta[11]
        );
        const float3 prev_cam_eye = make_float3(params.previous_cam_eye[0], params.previous_cam_eye[1], params.previous_cam_eye[2]);
        const float3 prev_cam_u = make_float3(params.previous_cam_u[0], params.previous_cam_u[1], params.previous_cam_u[2]);
        const float3 prev_cam_v = make_float3(params.previous_cam_v[0], params.previous_cam_v[1], params.previous_cam_v[2]);
        const float3 prev_cam_w = make_float3(params.previous_cam_w[0], params.previous_cam_w[1], params.previous_cam_w[2]);
        const float2 prev_pixel = reprojectToPreviousFrame(
            prev_local, prev_cam_eye, prev_cam_u, prev_cam_v, prev_cam_w,
            params.image_width, params.image_height);
        const uint3 idx = optixGetLaunchIndex();
        const unsigned int pixel_idx = idx.y * params.image_width + idx.x;
        if (prev_pixel.x >= 0.0f) {
            params.flow[pixel_idx] = make_float2(
                static_cast<float>(idx.x) - prev_pixel.x,
                static_cast<float>(idx.y) - prev_pixel.y);
        } else {
            params.flow[pixel_idx] = make_float2(0.0f, 0.0f);  // disoccluded/degenerate: no motion hint
        }
    }
}
```

Each of the 9 call sites (`writeDenoiseGuides(material_color, normal);` or, in `hit_triangle.cu`, `writeDenoiseGuides(mesh_color, geom.normal);`) becomes a one-line signature extension adding the already-locally-available `hit_point` (or `geom.hit_point` in `hit_triangle.cu`) as the third argument — e.g. `writeDenoiseGuides(material_color, normal, hit_point);`.

- [ ] **Step 1: Write the failing test — flow buffer reflects known object motion**

Extend `TemporalMotionSuite.scala`: add a test that moves a single instance a known distance between two temporal-mode frames with a FIXED camera, downloads the flow buffer (new `@native` accessor `getFlowBufferNative(): Array[Float]`, mirroring how other debug buffers are exposed in this codebase — check `RendererFixture`/`OptiXRenderer` for an existing buffer-download pattern to match, e.g. how `RenderResult.image` is retrieved), and asserts the flow value at the pixel where the instance is visible is non-zero and points in the geometrically expected direction (same sign as the instance's on-screen movement direction). Confirm it fails to compile (buffer accessor doesn't exist) before implementing.

- [ ] **Step 2: Implement — `params.instance_motion`, `params.flow`, `previous_cam_*` fields**

Add to `OptiXData.h`'s params struct (near the other `denoise_*` pointer fields): `float* instance_motion; // 12 floats per instance, or nullptr when temporal denoising is off` and `float2* flow; // one per pixel, or nullptr when off`, and `float previous_cam_eye[3]; float previous_cam_u[3]; float previous_cam_v[3]; float previous_cam_w[3];`.

- [ ] **Step 3: Implement — buffer allocation + per-frame upload**

`BufferManager`: add `ensureFlowBuffer(width, height)` / `getFlowBuffer()` mirroring the existing `ensureDenoiseBuffers`/`getDenoiseAlbedoBuffer` pattern (same file, same class — read the existing methods for the exact style before adding). In `OptiXWrapper::render()`, gated on `impl->temporal_denoising_enabled`: allocate the flow buffer, build a host-side `std::vector<float>` of `12 * instances.size()` by calling `computeInstanceMotionDelta` once per instance (reusing Task 1's function directly, not `getInstanceMotionDelta`'s public wrapper — call the internal helper to avoid redundant identity-fallback logic), upload it to a device buffer, and set `params.instance_motion`/`params.flow`/`params.previous_cam_*` before the launch. When `temporal_denoising_enabled` is false, leave both pointers `nullptr` (matches the shader's own null-check gate, so this is a true no-op for `Off`/`Final`).

- [ ] **Step 4: Implement — the two device functions + `writeDenoiseGuides` extension + 9 call-site updates**

Add `solveBasisCoefficients`/`reprojectToPreviousFrame` to `helpers.cu` (near `writeDenoiseGuides`). Replace `writeDenoiseGuides` per the exact body above (new `hit_point` parameter, restructured guard, new flow-write block). Update all 9 call sites to pass `hit_point` (or `geom.hit_point`) as the third argument. On a primary-ray MISS (no geometry hit at all), `writeDenoiseGuides` is never called — `params.flow` for that pixel keeps whatever was already there; if the flow buffer isn't explicitly zeroed before each launch, add a `cudaMemset` to `BufferManager::ensureFlowBuffer` (Step 3) or the render-loop's per-frame setup so miss pixels report zero motion rather than stale data from a previous frame's buffer contents.

- [ ] **Step 5: Rebuild, run the new test**

```bash
sbt "project optixJni" nativeCompile
sbt "testOnly io.github.lene.optix.TemporalMotionSuite"
```

Expected: PASS, flow vector points in the geometrically correct direction. If wrong sign/direction, check: `reprojectToPreviousFrame`'s `prev_pixel` should be SUBTRACTED from `(idx.x, idx.y)` (current minus previous — the OptiX temporal denoiser convention is "pixel movement from previous to current frame," matching `optix_types.h:1991`), not the reverse.

- [ ] **Step 6: Run full existing suite for zero regression**

```bash
sbt "project optixJni" nativeTest
sbt test
```

- [ ] **Step 7: Commit**

```bash
git add src/main/native/shaders/helpers.cu \
    src/main/native/shaders/hit_sphere.cu src/main/native/shaders/hit_cone.cu \
    src/main/native/shaders/hit_curve.cu src/main/native/shaders/hit_cylinder.cu \
    src/main/native/shaders/hit_hexadecachoron4d.cu src/main/native/shaders/hit_menger4d.cu \
    src/main/native/shaders/hit_plane.cu src/main/native/shaders/hit_sierpinski4d.cu \
    src/main/native/shaders/hit_triangle.cu \
    src/main/native/include/OptiXData.h \
    src/main/native/BufferManager.cpp src/main/native/include/BufferManager.h \
    src/main/native/OptiXWrapper.cpp src/main/native/include/OptiXWrapper.h \
    src/main/native/JNIBindings.cpp src/main/scala/io/github/lene/optix/OptiXRenderer.scala \
    src/test/scala/io/github/lene/optix/TemporalMotionSuite.scala
git commit -m "feat(temporal): compute per-pixel flow vectors from camera+object motion"
```

---

### Task 3: `DenoiserManager` TEMPORAL model kind + previousOutput retention

**Repo:** optix-jni

**Files:**
- Modify: `src/main/native/include/DenoiserManager.h`, `src/main/native/DenoiserManager.cpp`
- Modify: `src/main/native/OptiXWrapper.cpp` (denoiser construction at `:1380-1401`, invoke call at `:1964-1971`)

**Interfaces:**
- Produces: `DenoiserManager` constructor gains a `bool temporal` parameter (default-compatible with existing call sites: `DenoiserManager(context, guide_albedo, guide_normal, temporal=false)`); `denoiseFloat4` gains a `CUdeviceptr flow` parameter (default `0` for HDR-mode callers); new private members `CudaBuffer<float> previous_output_buffer_` and `bool has_previous_output_ = false`.
- Consumes: Task 2's `params.flow` device buffer (uploaded via `BufferManager`, passed through by `OptiXWrapper::render()`'s call to `denoiseFloat4`).

- [ ] **Step 1: Write the failing test — TEMPORAL model kind is used, previousOutput chains across two invokes**

This needs a native (C++) test, since `denoiser_`'s internal state isn't JNI-exposed. Add to the existing native GoogleTest suite (find the existing pattern — grep `src/main/native/tests/` for any `Denoiser`-related test; if none exists, create `src/main/native/tests/DenoiserManagerTest.cpp` mirroring `OptiXContextTest.cpp`'s structure). Test: construct a `DenoiserManager` with `temporal=true`, call `denoiseFloat4` twice in a row with a valid `flow` buffer on the second call, assert both calls return `true` (no crash/failure) and that the SECOND call's OptiX invocation used `params.temporalModeUsePreviousLayers == 1` (expose this via a small testability accessor, e.g. a `bool lastInvokeUsedPreviousLayers() const` getter, since there's no public way to inspect `OptixDenoiserParams` after the fact otherwise). Confirm this fails to compile against current code (constructor doesn't accept `temporal`).

- [ ] **Step 2: Implement — `DenoiserManager.h`**

```cpp
class DenoiserManager {
public:
    DenoiserManager(OptixDeviceContext context, bool guide_albedo, bool guide_normal, bool temporal = false);
    ~DenoiserManager();

    DenoiserManager(const DenoiserManager&) = delete;
    DenoiserManager& operator=(const DenoiserManager&) = delete;

    bool usesAlbedoGuide() const { return guide_albedo_; }
    bool usesNormalGuide() const { return guide_normal_; }
    bool isTemporal() const { return temporal_; }

    bool denoiseFloat4(
        int width,
        int height,
        CUdeviceptr input,
        CUdeviceptr output,
        CUdeviceptr albedo,
        CUdeviceptr normal,
        CUdeviceptr flow = 0
    );

    bool lastInvokeUsedPreviousLayers() const { return last_invoke_used_previous_layers_; }

private:
    OptixImage2D makeImage(CUdeviceptr data, int width, int height) const;
    void ensureSetup(int width, int height);

    OptixDenoiser denoiser_ = nullptr;
    bool guide_albedo_ = false;
    bool guide_normal_ = false;
    bool temporal_ = false;
    bool has_previous_output_ = false;
    bool last_invoke_used_previous_layers_ = false;
    unsigned int setup_width_ = 0;
    unsigned int setup_height_ = 0;
    size_t scratch_size_ = 0;
    OptixDenoiserSizes sizes_{};
    CudaBuffer<unsigned char> state_buffer_;
    CudaBuffer<unsigned char> scratch_buffer_;
    CudaBuffer<float> hdr_intensity_buffer_;
    CudaBuffer<float> previous_output_buffer_;  // float4 per pixel, temporal mode only
};
```

- [ ] **Step 3: Implement — `DenoiserManager.cpp`**

Constructor (replace lines 17-37): select model kind based on `temporal`, store the flag:

```cpp
DenoiserManager::DenoiserManager(
    OptixDeviceContext context,
    bool guide_albedo,
    bool guide_normal,
    bool temporal
) : guide_albedo_(guide_albedo), guide_normal_(guide_normal), temporal_(temporal) {
    if (context == nullptr) {
        throw std::runtime_error("OptiX context is null");
    }

    OptixDenoiserOptions options = {};
    options.guideAlbedo = guide_albedo ? 1u : 0u;
    options.guideNormal = guide_normal ? 1u : 0u;
    options.denoiseAlpha = OPTIX_DENOISER_ALPHA_MODE_COPY;

    const OptixDenoiserModelKind model_kind =
        temporal ? OPTIX_DENOISER_MODEL_KIND_TEMPORAL : OPTIX_DENOISER_MODEL_KIND_HDR;

    OPTIX_CHECK(optixDenoiserCreate(
        context,
        model_kind,
        &options,
        &denoiser_
    ));
}
```

`denoiseFloat4` (replace the signature and body, `:46-111`): add `flow` param, wire `guide_layer.flow`, `layer.previousOutput`, `params.temporalModeUsePreviousLayers`, manage `previous_output_buffer_`:

```cpp
bool DenoiserManager::denoiseFloat4(
    int width,
    int height,
    CUdeviceptr input,
    CUdeviceptr output,
    CUdeviceptr albedo,
    CUdeviceptr normal,
    CUdeviceptr flow
) {
    if (denoiser_ == nullptr || width <= 0 || height <= 0 || input == 0 || output == 0) {
        return false;
    }
    if ((guide_albedo_ && albedo == 0) || (guide_normal_ && normal == 0)) {
        return false;
    }

    try {
        ensureSetup(width, height);
        if (temporal_) {
            const size_t npix = static_cast<size_t>(width) * static_cast<size_t>(height) * 4;
            previous_output_buffer_.allocate(npix);
        }

        OptixDenoiserGuideLayer guide_layer = {};
        if (guide_albedo_) {
            guide_layer.albedo = makeImage(albedo, width, height);
        }
        if (guide_normal_) {
            guide_layer.normal = makeImage(normal, width, height);
        }
        if (temporal_ && flow != 0) {
            OptixImage2D flow_image = {};
            flow_image.data = flow;
            flow_image.width = static_cast<unsigned int>(width);
            flow_image.height = static_cast<unsigned int>(height);
            flow_image.rowStrideInBytes = static_cast<unsigned int>(width) * 2 * sizeof(float);
            flow_image.pixelStrideInBytes = 2 * sizeof(float);
            flow_image.format = OPTIX_PIXEL_FORMAT_FLOAT2;
            guide_layer.flow = flow_image;
        }

        OptixDenoiserLayer layer = {};
        layer.input = makeImage(input, width, height);
        layer.output = makeImage(output, width, height);
        layer.type = OPTIX_DENOISER_AOV_TYPE_BEAUTY;
        if (temporal_ && has_previous_output_) {
            layer.previousOutput = makeImage(previous_output_buffer_.get(), width, height);
        }

        OPTIX_CHECK(optixDenoiserComputeIntensity(
            denoiser_,
            nullptr,
            &layer.input,
            hdr_intensity_buffer_.get(),
            scratch_buffer_.get(),
            sizes_.computeIntensitySizeInBytes
        ));

        OptixDenoiserParams params = {};
        params.hdrIntensity = hdr_intensity_buffer_.get();
        params.blendFactor = 0.0f;
        params.temporalModeUsePreviousLayers = (temporal_ && has_previous_output_) ? 1u : 0u;
        last_invoke_used_previous_layers_ = params.temporalModeUsePreviousLayers != 0u;

        OPTIX_CHECK(optixDenoiserInvoke(
            denoiser_,
            nullptr,
            &params,
            state_buffer_.get(),
            sizes_.stateSizeInBytes,
            &guide_layer,
            &layer,
            1,
            0,
            0,
            scratch_buffer_.get(),
            scratch_size_
        ));

        CUDA_CHECK(cudaDeviceSynchronize());

        if (temporal_) {
            CUDA_CHECK(cudaMemcpy(
                reinterpret_cast<void*>(previous_output_buffer_.get()),
                reinterpret_cast<void*>(output),
                static_cast<size_t>(width) * static_cast<size_t>(height) * 4 * sizeof(float),
                cudaMemcpyDeviceToDevice
            ));
            has_previous_output_ = true;
        }

        return true;
    } catch (const std::exception& e) {
        std::cerr << "[OptiX] Denoise failed: " << e.what() << std::endl;
        return false;
    }
}
```

**Regression guard:** every existing HDR-mode call site constructs with `temporal` defaulted to `false` (Step 2's default parameter) and calls `denoiseFloat4` without a `flow` arg (defaults to `0`) — `temporal_` stays `false`, every new `if (temporal_...)` branch is skipped, so the HDR/`Final` path executes the exact same sequence of OptiX calls as before this task, byte-for-byte.

- [ ] **Step 4: Wire `OptiXWrapper.cpp`'s construction + invoke call sites**

At the denoiser construction (`:1382-1395`), pass `impl->temporal_denoising_enabled` as the 4th constructor arg on both the guided and color-only fallback paths. At the invoke call (`:1964-1971`), pass the flow buffer (from Task 2's `BufferManager::getFlowBuffer()`, or `0` when `!temporal_denoising_enabled`) as the 7th argument.

- [ ] **Step 5: Rebuild, run native + Scala tests**

```bash
sbt "project optixJni" nativeCompile
sbt "project optixJni" nativeTest
sbt test
```

Expected: new native test passes; all existing Scala suites (`CausticsReferenceSuite` and any denoiser-touching test) unchanged.

- [ ] **Step 6: Commit**

```bash
git add src/main/native/include/DenoiserManager.h src/main/native/DenoiserManager.cpp \
    src/main/native/OptiXWrapper.cpp src/main/native/tests/DenoiserManagerTest.cpp
git commit -m "feat(temporal): OPTIX_DENOISER_MODEL_KIND_TEMPORAL + previousOutput retention"
```

---

### Task 4: menger wiring — `DenoiseMode.Temporal`

**Repo:** menger

**Files:**
- Modify: `menger-app/src/main/scala/menger/dsl/DenoiseMode.scala` (4 lines total, add one case)
- Modify: `menger-app/src/main/scala/menger/engines/BaseEngine.scala` (`:33-35`)
- Modify: `menger-app/src/main/scala/menger/engines/WithAnimation.scala` (`:108`)
- Create: a new Scala unit test under `menger-app/src/test/scala/menger/engines/` (find the existing `BaseEngine`/`WithAnimation` test-double pattern first — check for an existing `FakeRenderer`/mock in that test directory and reuse it)

**Interfaces:**
- Produces: `DenoiseMode` gains `case Temporal`; both denoise-wiring call sites call `setDenoisingEnabled` AND the new `setTemporalDenoisingEnabled` (Task 1's addition) based on `denoiseMode == Temporal`.

- [ ] **Step 1: Write the failing test**

In the menger test suite for `BaseEngine`/`WithAnimation` (locate the exact existing file and its renderer test-double before writing — do not invent a new mocking approach if one already exists), add a test asserting: when `denoiseMode == DenoiseMode.Temporal`, both `renderer.setDenoisingEnabled(true)` and `renderer.setTemporalDenoisingEnabled(true)` are invoked; when `denoiseMode == DenoiseMode.Final`, `setDenoisingEnabled(true)` is invoked but `setTemporalDenoisingEnabled` is NOT (or is invoked with `false` — match whatever the existing test double can distinguish). Confirm it fails to compile (`DenoiseMode.Temporal` doesn't exist).

- [ ] **Step 2: Implement**

`DenoiseMode.scala`:
```scala
package menger.dsl

enum DenoiseMode:
  case Off, Final, Temporal
```

`BaseEngine.scala` (`:33-35`, replace):
```scala
  protected def configureOutputMode(renderer: OptiXRenderer): Unit =
    renderer.setDenoisingEnabled(denoiseMode == DenoiseMode.Final || denoiseMode == DenoiseMode.Temporal)
    renderer.setTemporalDenoisingEnabled(denoiseMode == DenoiseMode.Temporal)
    if accumulationFrames > 1 then renderer.setAccumulationFrames(accumulationFrames)
```

`WithAnimation.scala` (`:108`, replace):
```scala
          renderer.setDenoisingEnabled(
            configs.denoiseMode == menger.dsl.DenoiseMode.Final ||
            configs.denoiseMode == menger.dsl.DenoiseMode.Temporal
          )
          renderer.setTemporalDenoisingEnabled(configs.denoiseMode == menger.dsl.DenoiseMode.Temporal)
```

- [ ] **Step 3: Run test, confirm PASS**

```bash
sbt "testOnly menger.engines.*"
```

- [ ] **Step 4: Run full existing suite for zero regression**

```bash
sbt test
```

**Regression guard:** `Off`/`Final` behavior unchanged (`setTemporalDenoisingEnabled(false)` is always sent explicitly now for those modes, but Task 1's native setter treats `false` as a true no-op/reset, matching current behavior exactly).

- [ ] **Step 5: Commit**

```bash
git add menger-app/src/main/scala/menger/dsl/DenoiseMode.scala \
    menger-app/src/main/scala/menger/engines/BaseEngine.scala \
    menger-app/src/main/scala/menger/engines/WithAnimation.scala \
    <new test file>
git commit -m "feat(temporal): wire DenoiseMode.Temporal through BaseEngine/WithAnimation"
```

---

### Task 5: Deterministic flicker-metric test

**Repo:** optix-jni (JNI-level) or menger (if a real multi-frame `sceneFunction` loop is unavoidable — see below)

**Files:**
- Create: `src/test/scala/io/github/lene/optix/TemporalFlickerSuite.scala`

**Interfaces:**
- Consumes: `renderer.setDenoisingEnabled`, `renderer.setTemporalDenoisingEnabled` (Task 1/4), `renderer.getCausticsStats` or direct pixel sampling for the ROI-brightness metric (mirror `CausticsRoughGlassSuite`'s pattern from Phase 3 — whole-region statistic, not raw pixel diff).

This test can be written entirely at the optix-jni JNI level (no menger DSL/animation loop needed): drive N frames directly by calling `renderer.addSphereInstance`/`clearAllInstances`/`setCamera` in a loop with a scripted, deterministic per-frame delta (e.g. move the sphere's X position by a fixed increment each frame), exactly like `TemporalMotionSuite`'s two-frame tests but extended to N frames. This avoids needing a real `WithAnimation`/`sceneFunction` harness.

- [ ] **Step 1: Write the RED-that-must-already-be-true canary, then the true RED**

```scala
package io.github.lene.optix

import menger.common.Color
import menger.common.Const
import menger.common.ImageSize
import menger.common.Material
import menger.common.Vector
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Phase 4: the temporal denoiser must produce LESS frame-to-frame caustic-region flicker than
  * the old spatial-only HDR denoiser, for the same deterministic scripted animation (fixed
  * camera, a glass sphere moving a fixed increment per frame, fixed photon-seed schedule --
  * caustics PPM seeding is frame-independent, see Phase 3/roadmap grounding, so this animation
  * is reproducible run-to-run regardless of denoise mode).
  */
class TemporalFlickerSuite extends AnyFlatSpec with Matchers with RendererFixture:

  private val runningUnderSanitizer: Boolean =
    sys.env.get("RUNNING_UNDER_COMPUTE_SANITIZER").contains("true")

  private val imageSize: ImageSize = ImageSize(200, 150)
  private val frameCount = 5
  private val stepX = 0.3f
  private val photonsPerIter = 100000
  private val causticIterations = 4
  private val lightIntensity = 500.0f

  private def sphereTransformAt(x: Float): Array[Float] =
    Array(1f, 0f, 0f, x, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f)

  /** Renders `frameCount` frames of a sphere sliding along X, returns the caustic-ROI mean
    * brightness for each frame (floor half of the image, mirrors Phase 3's dilution-avoidance
    * reasoning: restrict to the region that can plausibly change, don't average in static sky). */
  private def renderAnimationRoiSeries(temporal: Boolean): Seq[Double] =
    renderer.setDenoisingEnabled(true)
    renderer.setTemporalDenoisingEnabled(temporal)
    renderer.clearPlanes()
    renderer.addPlane(1, positive = true, Const.defaultFloorPlaneY)
    renderer.setCamera(
      Vector[3](0.0f, 4.0f, 8.0f), Vector[3](0.0f, 0.0f, 0.0f), Vector[3](0.0f, -1.0f, 0.0f), 45.0f)
    renderer.setLight(Vector[3](0.0f, -1.0f, 0.0f), lightIntensity)
    renderer.enableCaustics(photonsPerIter, causticIterations)
    val glass = Material(Color(0.95f, 0.95f, 1.0f, 0.5f), ior = Const.iorGlass)
    (0 until frameCount).map { f =>
      renderer.clearAllInstances()
      renderer.addSphereInstance(sphereTransformAt(f * stepX), glass)
      val result = renderer.renderWithStats(imageSize).get
      val roiPixels = for
        y <- imageSize.height / 2 until imageSize.height
        x <- 0 until imageSize.width
      yield ImageValidation.getRGBAt(result.image, imageSize, x, y)
      roiPixels.map(p => (p.r.toDouble + p.g.toDouble + p.b.toDouble) / 3.0).sum / roiPixels.length
    }

  private def meanAbsFrameToFrameDelta(series: Seq[Double]): Double =
    series.sliding(2).map { case Seq(a, b) => math.abs(b - a) }.sum / (series.length - 1)

  behavior of "Temporal denoiser flicker reduction"

  it should "have measurable frame-to-frame flicker with the spatial-only (Final) denoiser [canary]" in:
    if runningUnderSanitizer then cancel("Skipped under compute-sanitizer (too slow)")
    val finalSeries = renderAnimationRoiSeries(temporal = false)
    val finalDelta = meanAbsFrameToFrameDelta(finalSeries)
    withClue(s"Final-mode frame-to-frame ROI delta series=$finalSeries meanDelta=$finalDelta; " +
      "this canary must show nonzero flicker today (spatial-only denoising has no cross-frame " +
      "memory) -- if it's ~0, the metric itself is insensitive and the real assertion below " +
      "would be meaningless. ") {
      finalDelta should be > 0.5
    }

  it should "reduce frame-to-frame flicker with the temporal denoiser vs the canary above" in:
    if runningUnderSanitizer then cancel("Skipped under compute-sanitizer (too slow)")
    val finalSeries = renderAnimationRoiSeries(temporal = false)
    val finalDelta = meanAbsFrameToFrameDelta(finalSeries)
    val temporalSeries = renderAnimationRoiSeries(temporal = true)
    val temporalDelta = meanAbsFrameToFrameDelta(temporalSeries)
    withClue(s"Final meanDelta=$finalDelta Temporal meanDelta=$temporalDelta " +
      s"(finalSeries=$finalSeries temporalSeries=$temporalSeries); temporal mode must reduce " +
      "flicker relative to the spatial-only baseline on the SAME scripted animation. ") {
      temporalDelta should be < finalDelta
    }

end TemporalFlickerSuite
```

- [ ] **Step 2: Run and confirm the canary passes (proves the metric is sensitive) but the real assertion fails until Tasks 1-4 are wired**

```bash
sbt "testOnly io.github.lene.optix.TemporalFlickerSuite"
```

Expected: canary test PASSES (Final mode genuinely flickers on this scripted animation — if it doesn't, the animation/metric needs adjusting, e.g. a larger `stepX` or more frames, BEFORE trusting the second test at all). Second test's expected status at this point in the plan (after Tasks 1-4 are already done, per this plan's task order) should already be GREEN — if Tasks 1-4 landed correctly, temporal mode should already reduce flicker. If it's still RED here, apply systematic-debugging on the Task 1-3 wiring (check `has_previous_output_`/`has_previous_frame` are actually `true` by the second rendered frame, check the flow buffer isn't all-zero).

- [ ] **Step 3: Tune `stepX`/`frameCount`/threshold empirically if needed**

If the canary doesn't show clear flicker, or the temporal-vs-final comparison is noisy, adjust `stepX` (larger = more motion = more baseline flicker to reduce) and document the empirical choice in a comment, following `CausticsCoverageSuite`'s `MaxEnergyConservationErrorRatio` convention (state the observed values, not just the chosen threshold).

- [ ] **Step 4: Run full existing suite for zero regression**

```bash
sbt "project optixJni" nativeTest
sbt test
```

- [ ] **Step 5: Commit**

```bash
git add src/test/scala/io/github/lene/optix/TemporalFlickerSuite.scala
git commit -m "test(temporal): deterministic flicker-reduction metric (Final baseline vs Temporal)"
```

---

### Task 6: menger integration test + manual scene

**Repo:** menger

**Files:**
- Modify: `menger/build.sbt` (`optixJniDependency` — set after Task 8 publishes 0.1.20, same deferred-step pattern as Phase 3's Task 4)
- Modify: `scripts/integration-tests.sh`, `scripts/manual-test.sh`

**Interfaces:**
- Consumes: whatever CLI flag surfaces `DenoiseMode.Temporal` — check `MengerCLIOptions.scala` for the current `--denoise` flag's value parsing (confirmed `Off`/`Final` already map from CLI strings; add `temporal` as a third accepted value there too, since `DenoiseMode.Temporal` alone doesn't reach the CLI without this).

- [ ] **Step 1: Add the CLI flag value**

In `MengerCLIOptions.scala`, find where `--denoise final`/`--denoise off` parse into `DenoiseMode` and add a `"temporal" -> DenoiseMode.Temporal` case (match the existing case-insensitivity/error-handling style at that site exactly).

- [ ] **Step 2: Add the integration test (smoke-test scope, not flicker validation)**

`integration-tests.sh` is single-frame-per-`run_test` (confirmed, Phase 3's Task 4 report) — true flicker validation lives in Task 5's suite. Add a smoke test confirming a `--denoise temporal` render doesn't crash and produces a valid image:

```bash
test_temporal_denoise_smoke() {
    echo "Temporal denoiser smoke test (renders without crashing, valid image):"
    TIMEOUT=$CAUSTICS_TIMEOUT run_test "temporal denoise smoke" \
        --objects type=sphere:material=glass:pos=0,0,0 --caustics \
        --caustics-photons 1000 --caustics-iterations 1 \
        --plane y:-2 --light point:0,10,0:500 --denoise temporal
}
```

Register in the run-list alongside the other caustics tests. RED (missing reference) → generate reference → verify determinism, same cycle as every prior phase's integration test.

- [ ] **Step 3: Add the manual scene**

Append to `scripts/manual-test.sh` after the last caustics entry (Phase 3's `165-frosted-glass-caustics`): a moving-camera-and-sphere caustics animation with `--denoise temporal`, for visual comparison against the equivalent `--denoise final` run over the same motion. Since `manual-test.sh`'s `run_test` calls are single-shot renders (not true animations), the manual verification here is necessarily a single representative frame mid-motion — note this scoping explicitly in the scene's description string, and if menger's CLI supports an `--animate`/frame-sequence flag for manual runs, prefer that instead (check `MengerCLIOptions.scala` for animation-related flags before deciding).

- [ ] **Step 4: Commit**

```bash
git add menger-app/src/main/scala/menger/MengerCLIOptions.scala \
    scripts/integration-tests.sh scripts/manual-test.sh scripts/reference-images/temporal_denoise_smoke.png
git commit -m "test(temporal): integration smoke test + manual scene for temporal denoiser"
```

---

### Task 7: optix-jni release + menger consume

**Repo:** both, same cycle as Phases 2/3.

- [ ] **Step 1:** optix-jni: bump `build.sbt` to `0.1.20`, CHANGELOG entry (temporal denoiser, cross-frame instance-motion tracking + topology-mismatch fallback, camera+object motion vectors), full pre-push gate green, commit.
- [ ] **Step 2:** Push branch, PR, explicit user confirmation, merge, tag `v0.1.20`, `gh workflow run ci.yml --ref v0.1.20` (tag push alone doesn't trigger CI in this repo, confirmed both Phase 2 and Phase 3), verify all 4 Maven Central artifacts HTTP 200.
- [ ] **Step 3:** menger: bump `build.sbt`'s `optixJniDependency` to `0.1.20` (finishing Task 6's deferred pin bump), version bump across the 5 standard files (ask user for the number, do not infer — prior was 0.8.5), CHANGELOG entry, full DoD gate green, commit, push (explicit confirmation), monitor GitLab pipeline to green.

---

## Notes for the executor

- **The load-bearing novel math in this plan is the camera-basis reprojection** (`solveBasisCoefficients`/`reprojectToPreviousFrame`, Task 2) and the **3×3 affine-inverse instance-motion delta** (`invert3x3`/`computeInstanceMotionDelta`, Task 1). Both are closed-form, fully specified above — implement them exactly as written rather than re-deriving, but DO verify the sign conventions against `TemporalMotionSuite`'s numeric assertions (Task 1 Step 8, Task 2 Step 5) before trusting them, since a sign error here would silently produce wrong-direction motion vectors that still compile and run without crashing.
- **Task ordering is load-bearing**: Task 2 depends on Task 1's `computeInstanceMotionDelta`/camera-history snapshot; Task 3 depends on Task 2's flow buffer; Task 4 depends on Task 1's `setTemporalDenoisingEnabled`; Task 5 depends on Tasks 1-4 all being wired correctly (it's a proof task, not new production code); Task 6 depends on Task 4's `DenoiseMode.Temporal` plus a published optix-jni artifact (Task 7's release happens LAST in execution order despite being numbered — same deferred-pin-bump pattern as Phase 3).
- This ray tracer's camera model is `eye + orthonormal-ish basis (u, v, w) + fov` (baked into `u`/`v`'s magnitude), **not** a 4×4 view-projection matrix — do not introduce one; the closed-form basis-solve above is the correct, minimal approach for this codebase's actual camera representation.
- `clearAllInstances()` does not touch `denoiser_manager` or any Task 1-3 temporal state (confirmed by direct read of its full body) — no new cleanup logic needed there.
- If Task 5's flicker metric proves hard to tune (noisy, or the canary itself is unreliable), that is itself useful signal about Tasks 1-3's correctness — do not weaken the test to force a pass; escalate to the user with the actual measured series if 2-3 tuning attempts don't produce a clean canary+real-assertion pair.
