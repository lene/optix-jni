# Caustics Coverage Net (Phase 1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Lock four shipped-but-unasserted end-to-end caustic *deposit* behaviors (tinted-glass caustic color, reflective-caustic presence, PPM dispersive deposit, energy conservation) with regression tests, plus menger integration + manual scenes — changing zero production code.

**Architecture:** New optix-jni ScalaTest suite (`CausticsCoverageSuite`) that renders single-object caustic scenes through `RendererFixture` and asserts on `CausticsStats` + per-channel region means of the rendered RGBA8 buffer. Menger gains headless integration tests with deterministic references and manual visual scenes. These are **characterization tests**: they assert current behavior, so they pass on the current build; a *failure* means a real bug (→ systematic-debugging), not a test to relax.

**Tech Stack:** Scala 3, ScalaTest `AnyFlatSpec`, OptiX JNI (`OptiXRenderer`), menger-common (`Material`, `Color`, `Const`, `ImageSize`), bash integration/manual scripts, ImageMagick `compare` for reference diffs.

## Global Constraints

- Scala 3 only; no `var`/`while`/`asInstanceOf`/`throw` in production (tests may use `@SuppressWarnings` + `scalafix:ok` as existing caustics suites do). Max line length 100. No magic numbers — named constants.
- GPU tests must self-skip when no GPU: guard with `assume(OptiXRenderer.isLibraryLoaded, ...)` via `RendererFixture`, and cancel under compute-sanitizer: `if runningUnderSanitizer then cancel(...)`.
- `Material` (menger-common 0.1.6): `Material(color: Color, ior: Float = 1.0f, roughness: Float = 0.5f, metallic: Float = 0.0f, specular: Float = 0.5f, emission: Float = 0.0f, filmThickness: Float = 0.0f, dispersion: Float = 0.0f, ...)`.
- `Const.iorGlass = 1.5f`, `Const.iorDiamond = 2.42f`, `Const.defaultFloorPlaneY`.
- `enableCaustics(photonsPerIter: Int = 100000, iterations: Int = 10, initialRadius: Float = 1.0f, alpha: Float = 0.7f)`.
- `CausticsStats` fields available: `refractionEvents`, `tirEvents`, `photonsDeposited`, `hitPointsWithFlux`, `totalFluxEmitted`, `totalFluxDeposited`, `totalFluxAbsorbed`, `totalFluxReflected`, `maxCausticBrightness`, `avgFloorBrightness`; derived `energyConservationError: Double`.
- `renderer.renderWithStats(imageSize).get: RenderResult`; `RenderResult.image: Array[Byte]` is row-major RGBA8, length `width*height*4`.
- `ImageValidation.getRGBAt(imageData: Array[Byte], size: ImageSize, x: Int, y: Int): RGB` where `RGB(r: Int, g: Int, b: Int)`.
- Proven-visible caustic framing (mirror `CausticsReferenceSuite.ReferenceScene`): radius-1 glass sphere at origin, floor at `Const.defaultFloorPlaneY`, **directional** light `(0,-1,0)` at **intensity 500** (a dim light quantizes the caustic to 0 in 8-bit), camera eye `(0,4,8)` look-at origin, up `(0,-1,0)` (inverted), FOV 45.
- menger CLI: object color `color=#RRGGBB`; material presets include `glass, glass-dispersive, diamond-dispersive, chrome, metal`; caustics via `--caustics --caustics-photons N --caustics-iterations M`; lights `point:x,y,z:intensity`; floor `--plane y:-2`.
- menger repo policy: every rendering feature ships a headless integration test **and** a manual scene; reference-image diffs resolved in the same commit; verify determinism (run-to-run identical) before committing references.
- Reflective caustic is the Fresnel-reflected photon component off refractive glass (caustic targets are IOR > 1.05 only), so it is asserted at unit level via `totalFluxReflected` and does **not** get a standalone menger scene.

---

### Task 1: Coverage suite scaffold + tinted-glass caustic color

**Repo:** optix-jni

**Files:**
- Create: `src/test/scala/io/github/lene/optix/caustics/CausticsCoverageSuite.scala`

**Interfaces:**
- Produces: `CausticsCoverageSuite` with private helpers `setupGlassScene(material: Material): Unit`, `renderGlassCaustic(material: Material): (CausticsStats, RenderResult)` (caustics on, returns stats), and `causticChannelDelta(material: Material): (Double, Double, Double)` (whole-image on−off per channel), reused by Tasks 2–4.

- [ ] **Step 1: Write the suite with the tinted-glass test**

```scala
package io.github.lene.optix.caustics

import io.github.lene.optix.RendererFixture
import io.github.lene.optix.ImageValidation
import io.github.lene.optix.CausticsStats
import io.github.lene.optix.RenderResult
import menger.common.Color
import menger.common.Const
import menger.common.ImageSize
import menger.common.Material
import menger.common.Vector
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Coverage net (Phase 1): characterization tests locking shipped caustic *deposit* behaviors.
  * These assert current behavior end-to-end (the physics calculations are already covered by
  * CausticsValidationSuite). A failure here means a real regression, not a test to relax.
  */
class CausticsCoverageSuite extends AnyFlatSpec with Matchers with RendererFixture:

  private val runningUnderSanitizer: Boolean =
    sys.env.get("RUNNING_UNDER_COMPUTE_SANITIZER").contains("true")

  private val imageSize: ImageSize = ImageSize(200, 150)
  private val sphereRadius = 1.0f
  private val photonsPerIter = 100000
  private val causticIterations = 4
  // A dim light makes the caustic quantize to 0 in 8-bit RGBA (on/off delta identically zero).
  // 500 is the proven-visible value from CausticsReferenceSuite.ReferenceScene.
  private val lightIntensity = 500.0f

  // 4x3 row-major transform: uniform scale r, centered at origin.
  private val sphereTransform: Array[Float] =
    Array(sphereRadius, 0f, 0f, 0f, 0f, sphereRadius, 0f, 0f, 0f, 0f, sphereRadius, 0f)

  // Known-good VISIBLE-caustic framing, copied verbatim from CausticsReferenceSuite.ReferenceScene
  // (which asserts causticBrightness > 0.01 in the rendered image, proving renderWithStats
  // composites the caustic into the pixels): radius-1 glass sphere over the floor, directional light
  // straight down at intensity 500, camera set back and up looking at the sphere centre, up-vector
  // inverted for correct orientation. `setCamera`'s 2nd arg is a look-at POINT;
  // `setLight(direction, intensity)` is a DIRECTIONAL light. This exact framing is required — an
  // invented camera or a dim light leaves the caustic out of frame / below 8-bit quantization,
  // making the on/off delta identically zero.
  private def setupGlassScene(material: Material): Unit =
    renderer.clearAllInstances()
    renderer.addSphereInstance(sphereTransform, material)
    renderer.clearPlanes()
    renderer.addPlane(1, positive = true, Const.defaultFloorPlaneY)
    renderer.setCamera(
      Vector[3](0.0f, 4.0f, 8.0f),    // eye
      Vector[3](0.0f, 0.0f, 0.0f),    // look-at the sphere centre
      Vector[3](0.0f, -1.0f, 0.0f),   // up (inverted for correct orientation, per ReferenceScene)
      45.0f
    )
    renderer.setLight(Vector[3](0.0f, -1.0f, 0.0f), lightIntensity)  // directional down

  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  private def renderGlassCaustic(material: Material): (CausticsStats, RenderResult) =
    setupGlassScene(material)
    renderer.enableCaustics(photonsPerIter, causticIterations)
    val result = renderer.renderWithStats(imageSize).get
    val stats = renderer.getCausticsStats
    if stats == null then fail("caustics produced no stats") // scalafix:ok DisableSyntax.null
    (stats, result)

  private def wholeImageChannelMeans(result: RenderResult): (Double, Double, Double) =
    val pixels = for
      y <- 0 until imageSize.height
      x <- 0 until imageSize.width
    yield ImageValidation.getRGBAt(result.image, imageSize, x, y)
    (
      pixels.map(_.r).sum.toDouble / pixels.length,
      pixels.map(_.g).sum.toDouble / pixels.length,
      pixels.map(_.b).sum.toDouble / pixels.length
    )

  /** Per-channel caustic contribution: whole-image mean(caustics on) − mean(caustics off) for the
    * same scene. Everything but the photon deposit is identical between the two renders, so the
    * delta isolates the caustic itself — no dependence on where it projects on screen. */
  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  private def causticChannelDelta(material: Material): (Double, Double, Double) =
    setupGlassScene(material)
    renderer.enableCaustics(photonsPerIter, causticIterations)
    val (onR, onG, onB) = wholeImageChannelMeans(renderer.renderWithStats(imageSize).get)
    setupGlassScene(material)
    renderer.disableCaustics()
    val (offR, offG, offB) = wholeImageChannelMeans(renderer.renderWithStats(imageSize).get)
    (onR - offR, onG - offG, onB - offB)

  behavior of "Caustics deposit coverage net"

  it should "tint the deposited caustic toward a red glass's colour" in:
    if runningUnderSanitizer then cancel("Skipped under compute-sanitizer (too slow)")
    val redGlass = Material(Color(1.0f, 0.2f, 0.2f, 0.5f), ior = Const.iorGlass)
    val (dr, dg, db) = causticChannelDelta(redGlass)
    withClue(s"caustic contribution (on−off) R=$dr G=$dg B=$db; a red glass's caustic must add " +
      "more red than green/blue, and must actually be present (R delta > 0). ") {
      dr should be > 0.0
      dr should be > dg
      dr should be > db
    }

end CausticsCoverageSuite
```

- [ ] **Step 2: Run the test; confirm it passes AND the tint delta is real (non-vacuous)**

Run (from `optix-jni/`, with the standard CUDA/OptiX env exported, via a wrapper script because a
bare `sbt` token is rejected by a Bash hook):
```bash
sbt "testOnly io.github.lene.optix.caustics.CausticsCoverageSuite"
```
Expected: PASS. The `withClue` prints `R=… G=… B=…` for the caustic contribution; confirm the R delta is clearly positive (caustic present) and exceeds G and B (red tint). If the R delta is ~0, the caustic is not in frame — STOP and report (adjust the camera look-at toward the floor and re-verify). If R does not dominate G/B, the tint is broken — STOP and apply systematic-debugging (do not weaken the assertion).

- [ ] **Step 3: Commit**

```bash
git add src/test/scala/io/github/lene/optix/caustics/CausticsCoverageSuite.scala
git commit -m "test(caustics): lock tinted-glass caustic colour (coverage net)"
```

---

### Task 2: Reflective-caustic presence

**Repo:** optix-jni

**Files:**
- Modify: `src/test/scala/io/github/lene/optix/caustics/CausticsCoverageSuite.scala`

**Interfaces:**
- Consumes: `renderGlassCaustic` from Task 1.

- [ ] **Step 1: Add the reflective-caustic test above `end CausticsCoverageSuite`**

```scala
  it should "deposit some reflected-photon flux for a glass caustic (reflective path active)" in:
    if runningUnderSanitizer then cancel("Skipped under compute-sanitizer (too slow)")
    val clearGlass = Material(Color(0.95f, 0.95f, 1.0f, 0.5f), ior = Const.iorGlass)
    val (stats, _) = renderGlassCaustic(clearGlass)
    withClue(s"totalFluxReflected=${stats.totalFluxReflected}; the P2 Fresnel-reflect path must " +
      "carry non-zero flux (photons Russian-roulette between reflect and refract). ") {
      stats.totalFluxReflected should be > 0.0
    }
```

- [ ] **Step 2: Run; confirm PASS**

Run:
```bash
sbt "testOnly io.github.lene.optix.caustics.CausticsCoverageSuite -- -z reflected"
```
Expected: PASS with `totalFluxReflected` > 0. If it is exactly 0, the reflect path is dead — STOP and investigate (systematic-debugging); do not delete the test.

- [ ] **Step 3: Commit**

```bash
git add src/test/scala/io/github/lene/optix/caustics/CausticsCoverageSuite.scala
git commit -m "test(caustics): lock reflective-caustic flux presence (coverage net)"
```

---

### Task 3: PPM dispersive caustic deposit

**Repo:** optix-jni

**Files:**
- Modify: `src/test/scala/io/github/lene/optix/caustics/CausticsCoverageSuite.scala`

**Interfaces:**
- Consumes: `causticChannelDelta` from Task 1.

- [ ] **Step 1: Add the dispersive-deposit test above `end CausticsCoverageSuite`**

Dispersion tints the deposited caustic chromatically. Using the caustic contribution (on−off) per channel isolates the caustic; its channel spread (max−min) is larger with dispersion on than off. Same neutral glass, only `dispersion` differs.

```scala
  it should "spread the caustic chromatically when dispersion is on (PPM spectral deposit)" in:
    if runningUnderSanitizer then cancel("Skipped under compute-sanitizer (too slow)")
    val baseColor = Color(0.95f, 0.95f, 1.0f, 0.5f)
    val plain = Material(baseColor, ior = Const.iorGlass, dispersion = 0.0f)
    val dispersive = Material(baseColor, ior = Const.iorGlass, dispersion = 1.0f)

    val (pr, pg, pb) = causticChannelDelta(plain)
    val plainSpread = math.max(pr, math.max(pg, pb)) - math.min(pr, math.min(pg, pb))

    val (dr, dg, db) = causticChannelDelta(dispersive)
    val dispSpread = math.max(dr, math.max(dg, db)) - math.min(dr, math.min(dg, db))

    withClue(s"caustic channel spread: dispersion-off=$plainSpread dispersion-on=$dispSpread; " +
      "the dispersive photon caustic must be more chromatic than the achromatic one. ") {
      dispSpread should be > plainSpread
    }
```

- [ ] **Step 2: Run; confirm PASS with a real spread gap**

Run:
```bash
sbt "testOnly io.github.lene.optix.caustics.CausticsCoverageSuite -- -z chromatically"
```
Expected: PASS, `dispersion-on` spread clearly greater than `dispersion-off`. If the two are equal, the PPM dispersive path is not tinting the deposit — STOP, investigate. If the gap is razor-thin, raise `causticIterations` for this test's renders (local copy) until the effect is stable.

- [ ] **Step 3: Commit**

```bash
git add src/test/scala/io/github/lene/optix/caustics/CausticsCoverageSuite.scala
git commit -m "test(caustics): lock PPM dispersive caustic chromatic deposit (coverage net)"
```

---

### Task 4: Energy-conservation guard

**Repo:** optix-jni

**Files:**
- Modify: `src/test/scala/io/github/lene/optix/caustics/CausticsCoverageSuite.scala`

**Interfaces:**
- Consumes: `renderGlassCaustic` from Task 1.

- [ ] **Step 1: Add a calibration probe test to read the current error**

```scala
  it should "report a bounded, deterministic caustic energy-conservation error" in:
    if runningUnderSanitizer then cancel("Skipped under compute-sanitizer (too slow)")
    val clearGlass = Material(Color(0.95f, 0.95f, 1.0f, 0.5f), ior = Const.iorGlass)
    val (statsA, _) = renderGlassCaustic(clearGlass)
    val (statsB, _) = renderGlassCaustic(clearGlass)
    info(s"energyConservationError run A=${statsA.energyConservationError} B=${statsB.energyConservationError}")
    statsA.totalFluxEmitted should be > 0.0
    statsA.energyConservationError shouldBe statsB.energyConservationError +- 1e-6
    statsA.energyConservationError should be < MaxEnergyConservationError
```

Add near the other constants (top of the class):
```scala
  // Calibrated in Step 2 from the observed error + headroom. Locks the accounting as a regression
  // guard: emitted flux == deposited + absorbed + reflected (+ escaped, which inflates the error).
  private val MaxEnergyConservationError: Double = 1.0
```

- [ ] **Step 2: Run, read the printed error, calibrate the ceiling**

Run:
```bash
sbt "testOnly io.github.lene.optix.caustics.CausticsCoverageSuite -- -z energy-conservation"
```
Read the `info(...)` line for the actual `energyConservationError` E (deterministic across runs A/B). Set `MaxEnergyConservationError` to `E * 1.25` rounded to two significant figures (headroom so noise doesn't flake it, tight enough to catch a real accounting regression). Re-run; expect PASS. If A ≠ B (non-deterministic), that itself is a finding — STOP and investigate before locking.

- [ ] **Step 3: Commit**

```bash
git add src/test/scala/io/github/lene/optix/caustics/CausticsCoverageSuite.scala
git commit -m "test(caustics): lock caustic energy-conservation error ceiling (coverage net)"
```

---

### Task 5: optix-jni gate

**Repo:** optix-jni

**Files:** none (validation only).

- [ ] **Step 1: Run the full pre-push gate feeding a ref line on stdin**

```bash
BRANCH=$(git rev-parse --abbrev-ref HEAD); SHA=$(git rev-parse HEAD)
printf 'refs/heads/%s %s refs/heads/%s 0000000000000000000000000000000000000000\n' "$BRANCH" "$SHA" "$BRANCH" | ./.git_hooks/pre-push 2>&1 | tee /tmp/optix-coverage-gate.log
```
Expected: `Tests: OK` (Scala count increased by 4), gtest unchanged, `Scalafix: OK`, `cppcheck: OK`. No version bump needed (this branch adds tests only; do not change `build.sbt` version). Fix any failure before proceeding.

- [ ] **Step 2: Commit** — nothing to commit; gate is validation.

---

### Task 6: menger colored-glass + PPM-dispersive caustics — integration tests

**Repo:** menger

**Files:**
- Modify: `scripts/integration-tests.sh` (add two `test_*` functions after `test_area_light_caustics`, register both in the run list near the other `test_*_caustics` entries)
- Create: `scripts/reference-images/colored_glass_caustics.png`, `scripts/reference-images/dispersive_caustics.png` (generated)

**Interfaces:**
- Consumes: existing `run_test`, `$CAUSTICS_TIMEOUT` from `integration-tests.sh`.

- [ ] **Step 1: Add the two integration test functions**

After `test_area_light_caustics() { ... }`:
```bash
test_colored_glass_caustics() {
    echo "Colored-glass caustics (red glass casts a tinted caustic):"
    TIMEOUT=$CAUSTICS_TIMEOUT run_test "colored glass caustics" \
        --objects type=sphere:material=glass:color=#FF3333 --caustics \
        --caustics-photons 1000 --caustics-iterations 1 \
        --plane y:-2 --light point:0,10,0:500
}

test_dispersive_caustics() {
    echo "Dispersive PPM caustics (spectral floor caustic):"
    TIMEOUT=$CAUSTICS_TIMEOUT run_test "dispersive caustics" \
        --objects type=sphere:material=glass-dispersive:ior=1.5 --caustics \
        --caustics-photons 1000 --caustics-iterations 1 \
        --plane y:-2 --light point:0,10,0:500
}
```

Register both (edit the test-list block that already contains `test_multiobject_caustics` / `test_area_light_caustics`):
```bash
    test_multiobject_caustics
    test_area_light_caustics
    test_colored_glass_caustics
    test_dispersive_caustics
```

- [ ] **Step 2: Confirm the two tests initially have no reference (expected FAIL)**

Stage the menger app binary and run just these two (sequential to avoid GPU-contention flakes):
```bash
sbt "project mengerApp" stage
BIN=$(pwd)/menger-app/target/universal/stage/bin/menger-app
PARALLEL_MODE=false ./scripts/integration-tests.sh "$BIN" \
    --filter "colored glass caustics" --filter "dispersive caustics"
```
Expected: both report MISSING reference (fail) — proves the tests run and reach the comparison.

- [ ] **Step 3: Generate references**

```bash
PARALLEL_MODE=false ./scripts/integration-tests.sh "$BIN" --update-references \
    --filter "colored glass caustics" --filter "dispersive caustics"
git status --short scripts/reference-images/   # must show ONLY the 2 new PNGs
```

- [ ] **Step 4: Verify determinism (re-run in compare mode; must match)**

```bash
PARALLEL_MODE=false ./scripts/integration-tests.sh "$BIN" \
    --filter "colored glass caustics" --filter "dispersive caustics"
```
Expected: both `- match (diff: 0%)`. If not deterministic, STOP and investigate before committing references.

- [ ] **Step 5: Commit**

```bash
git add scripts/integration-tests.sh \
    scripts/reference-images/colored_glass_caustics.png \
    scripts/reference-images/dispersive_caustics.png
git commit -m "test(caustics): integration scenes for colored-glass + dispersive caustics"
```

---

### Task 7: menger manual interactive scenes

**Repo:** menger

**Files:**
- Modify: `scripts/manual-test.sh` (append three scenes after the existing caustics block, near the `159-multiobject` / `160-area-light` entries)

- [ ] **Step 1: Add the three manual scenes**

```bash
run_test "Colored-glass caustics (red glass — the floor caustic should read visibly red)" "-o --objects type=sphere:material=glass:color=#FF3333 --camera-pos 0,1.5,6 --camera-lookat 0,0,0 --light point:0,10,0:500 --plane y:-2 --plane-color cccccc --caustics --caustics-photons 500000 --caustics-iterations 20 -s $OUTPUT_DIR/161-colored-glass-caustics.png"
run_test "Dispersive PPM caustics (glass-dispersive — spectral colour fringing in the floor caustic)" "-o --objects type=sphere:material=glass-dispersive:ior=1.5 --camera-pos 0,1.5,6 --camera-lookat 0,0,0 --light point:0,10,0:500 --plane y:-2 --plane-color cccccc --caustics --caustics-photons 500000 --caustics-iterations 20 -s $OUTPUT_DIR/162-dispersive-caustics.png"
run_test "Diamond-dispersive caustics (ior 2.42 — stronger dispersion, wider spectral spread)" "-o --objects type=sphere:material=diamond-dispersive:ior=2.42 --camera-pos 0,1.5,6 --camera-lookat 0,0,0 --light point:0,10,0:500 --plane y:-2 --plane-color cccccc --caustics --caustics-photons 500000 --caustics-iterations 20 -s $OUTPUT_DIR/163-diamond-dispersive-caustics.png"
```

- [ ] **Step 2: Smoke-run one manual scene to confirm it renders (not part of the headless suite)**

```bash
BIN=$(pwd)/menger-app/target/universal/stage/bin/menger-app
__GL_THREADED_OPTIMIZATIONS=0 xvfb-run -a "$BIN" --headless \
    --objects type=sphere:material=glass:color=#FF3333 --camera-pos 0,1.5,6 --camera-lookat 0,0,0 \
    --light point:0,10,0:500 --plane y:-2 --plane-color cccccc \
    --caustics --caustics-photons 200000 --caustics-iterations 8 --save-name /tmp/161-colored.png
```
Expected: `/tmp/161-colored.png` written; open it and confirm a visibly red floor caustic.

- [ ] **Step 3: Commit**

```bash
git add scripts/manual-test.sh
git commit -m "test(caustics): manual scenes for colored-glass + dispersive caustics"
```

---

### Task 8: menger version bump + DoD gate

**Repo:** menger

**Files:**
- Modify: `menger-app/build.sbt`, `.gitlab-ci.yml`, `menger-app/src/main/scala/menger/MengerCLIOptions.scala`, `docs/guide/user-guide.md`, `docs/USER_GUIDE.md` (version bump), `CHANGELOG.md` (new section)

**Interfaces:** none.

- [ ] **Step 1: Bump version 0.8.2 → 0.8.3 across all five files and add a CHANGELOG `[0.8.3]` section**

Replace `0.8.2` with `0.8.3` in: `menger-app/build.sbt` (`version :=`), `.gitlab-ci.yml` (`DEPLOYABLE_VERSION`), `MengerCLIOptions.scala` (`version("menger v0.8.2 ...")`), `docs/guide/user-guide.md` (`**Version**:`), `docs/USER_GUIDE.md` (`**Version**:`). Prepend to `CHANGELOG.md`:
```markdown
## [0.8.3] - <today>

### Added

- Regression coverage for shipped caustic behaviors: colored-glass caustic and PPM dispersive
  caustic integration + manual scenes. No behavior change.
```

- [ ] **Step 2: Run the full DoD gate**

```bash
LOCAL=$(git rev-parse HEAD)
printf 'refs/heads/feature/caustics-coverage-net %s refs/heads/feature/caustics-coverage-net <REMOTE_SHA>\n' "$LOCAL" | ./.git_hooks/pre-push 2>&1 | tee /tmp/menger-coverage-gate.log
```
(Use the current `origin/feature/...` SHA as `<REMOTE_SHA>`, or the merge-base with `origin/main` for a fresh branch.) Expected: `Passed: N/N` integration (including the 2 new scenes), coverage ≥ 80%, Valgrind/cppcheck/clang-tidy pass, `MENGER_PREPUSH_RC=0`. Fix any failure (e.g. an unrelated reference drift) in this same branch before pushing.

- [ ] **Step 3: Commit**

```bash
git add menger-app/build.sbt .gitlab-ci.yml \
    menger-app/src/main/scala/menger/MengerCLIOptions.scala \
    docs/guide/user-guide.md docs/USER_GUIDE.md CHANGELOG.md
git commit -m "chore: bump menger 0.8.2 -> 0.8.3 (caustics coverage net)"
```

---

## Notes for the executor

- **These are characterization tests.** The TDD "watch it fail" beat is inverted for Tasks 1–4: the test asserts *current* behavior and should pass immediately. The mandatory discipline is the *inspection* step — confirm the discriminator margin is real (not a vacuous pass). An unexpected **failure** is a real bug: switch to systematic-debugging, fix the root cause, keep the test. Never weaken an assertion to make a red test green.
- Push order once both gates are green: optix-jni branch → PR → merge (publishes nothing; tests only, no version bump, so no release tag). Then menger branch → push → monitor GitLab. Await explicit user confirmation before any push.
- optix-jni adds no new native/shader code, so `build.sbt` version stays put and no Maven release is cut for Phase 1.
