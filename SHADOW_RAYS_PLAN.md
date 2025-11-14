# Shadow Rays Implementation Plan (Feature 1.2)

**Status:** Ready to Implement
**Estimated Effort:** 4-6 hours
**Priority:** HIGH
**Risk:** LOW

---

## Overview

Add realistic hard shadows by casting shadow rays from surfaces to light sources. When a ray hits a surface, we trace a secondary ray toward the light to check if the path is occluded by other geometry.

---

## Implementation Steps

### Phase 1: Data Structure Updates (30 min)

#### 1.1 Update `optix-jni/src/main/native/include/OptiXData.h`

Add to `Params` struct:
```cpp
bool shadows_enabled;  // Enable shadow ray tracing
```

Add to `RayStats` struct:
```cpp
unsigned long long shadow_rays;  // Count of shadow rays traced
```

#### 1.2 Update `optix-jni/src/main/native/include/OptiXConstants.h`

Add constant:
```cpp
constexpr float SHADOW_RAY_OFFSET = 0.001f;  // Avoid self-intersection
```

---

### Phase 2: CUDA Shader Implementation (2-3 hours)

#### 2.1 Modify `optix-jni/src/main/native/shaders/sphere_combined.cu`

**Location:** Closest hit shader, lines 337-367 (opaque sphere lighting section)

**Current code:**
```cuda
const float3 light_dir_normalized = normalize(make_float3(
    params.light_dir[0], params.light_dir[1], params.light_dir[2]
));
const float diffuse = fmaxf(0.0f, dot(normal, light_dir_normalized));
const float lighting = AMBIENT_LIGHT_FACTOR + (1.0f - AMBIENT_LIGHT_FACTOR) * diffuse;
```

**New code with shadow rays:**
```cuda
const float3 light_dir_normalized = normalize(make_float3(
    params.light_dir[0], params.light_dir[1], params.light_dir[2]
));
const float diffuse = fmaxf(0.0f, dot(normal, light_dir_normalized));

// Shadow ray check (if enabled and surface is lit)
float shadow_factor = 1.0f;
if (params.shadows_enabled && diffuse > 0.0f) {
    // Offset origin to avoid self-intersection
    const float3 shadow_origin = hit_point + normal * SHADOW_RAY_OFFSET;

    // Trace shadow ray toward light
    unsigned int shadow_occluded = 0;

    optixTrace(
        params.handle,
        shadow_origin,
        light_dir_normalized,
        SHADOW_RAY_OFFSET,           // tmin
        1e16f,                        // tmax (effectively infinite)
        0.0f,                         // ray time
        OptixVisibilityMask(255),
        OPTIX_RAY_FLAG_TERMINATE_ON_FIRST_HIT,  // Early exit optimization
        0, 1, 0,                      // SBT offsets and strides
        shadow_occluded               // Payload: 0=visible, 1=occluded
    );

    shadow_factor = shadow_occluded > 0 ? 0.0f : 1.0f;

    // Update statistics
    if (params.stats) {
        atomicAdd(&params.stats->shadow_rays, 1ULL);
        atomicAdd(&params.stats->total_rays, 1ULL);
    }
}

const float lighting = AMBIENT_LIGHT_FACTOR + shadow_factor * (1.0f - AMBIENT_LIGHT_FACTOR) * diffuse;
```

#### 2.2 Add Shadow Ray Closest Hit Handler

Shadow rays don't need complex shading - just report occlusion:

```cuda
extern "C" __global__ void __closesthit__shadow() {
    // Shadow ray hit something - mark as occluded
    unsigned int* shadow_occluded = getPRD<unsigned int>();
    *shadow_occluded = 1;
}
```

**Note:** If using same closest-hit shader for all rays, add logic to detect shadow rays and exit early.

---

### Phase 3: C++ Layer (1 hour)

#### 3.1 Update `optix-jni/src/main/native/include/OptiXWrapper.h`

Add method declaration:
```cpp
void setShadows(bool enabled);
```

#### 3.2 Update `optix-jni/src/main/native/OptiXWrapper.cpp`

Implement method:
```cpp
void OptiXWrapper::setShadows(bool enabled) {
    impl->params.shadows_enabled = enabled;
    // Params are automatically synchronized to GPU before render
}
```

#### 3.3 Update `optix-jni/src/main/native/JNIBindings.cpp`

Add JNI binding:
```cpp
JNIEXPORT void JNICALL Java_menger_optix_OptiXRenderer_setShadows(
    JNIEnv* env, jobject obj, jboolean enabled) {
    try {
        OptiXWrapper* wrapper = getWrapper(env, obj);
        if (wrapper != nullptr) {
            wrapper->setShadows(enabled);
        }
    } catch (const std::exception& e) {
        std::cerr << "[JNI] Error in setShadows: " << e.what() << std::endl;
    }
}
```

---

### Phase 4: Scala Layer (30 min)

#### 4.1 Update `optix-jni/src/main/scala/menger/optix/OptiXRenderer.scala`

Add native method:
```scala
/** Enable/disable shadow rays for realistic lighting (default: false) */
@native def setShadows(enabled: Boolean): Unit
```

#### 4.2 Update `optix-jni/src/main/scala/menger/optix/RenderResult.scala`

Add field to case class:
```scala
case class RenderResult(
  image: Array[Byte],
  totalRays: Long,
  primaryRays: Long,
  reflectedRays: Long,
  refractedRays: Long,
  shadowRays: Long,           // NEW
  maxDepthReached: Int,
  minDepthReached: Int
)
```

#### 4.3 Update `src/main/scala/menger/MengerCLIOptions.scala`

Add CLI option:
```scala
val shadows: ScallopOption[Boolean] = opt[Boolean](
  required = false,
  default = Some(false),
  descr = "Enable shadow rays for realistic shadows (OptiX only)"
)
```

Add validation:
```scala
validateOpt(shadows, optix) { (sh, ox) =>
  if sh.getOrElse(false) && !ox.getOrElse(false) then
    Left("--shadows flag requires --optix flag")
  else Right(())
}
```

#### 4.4 Update `src/main/scala/menger/engines/OptiXEngine.scala`

Configure shadows during initialization:
```scala
override def create(): Unit =
  // ... existing initialization ...
  renderer.setShadows(shadows)
  // ...
```

---

### Phase 5: Automated Visual Testing (1-2 hours)

#### 5.1 Add Image Analysis Functions

**File:** `optix-jni/src/test/scala/menger/optix/ImageValidation.scala`

```scala
def regionBrightness(
  imageData: Array[Byte],
  width: Int,
  height: Int,
  region: String  // "top-left", "bottom-right", etc.
): Double = {
  val (startX, startY, endX, endY) = region match {
    case "top-left" => (0, 0, width / 2, height / 2)
    case "bottom-right" => (width / 2, height / 2, width, height)
    case "center" => (width / 4, height / 4, 3 * width / 4, 3 * height / 4)
    // ... more regions
  }

  var totalBrightness = 0.0
  var pixelCount = 0

  for (y <- startY until endY; x <- startX until endX) {
    val idx = (y * width + x) * 4
    val r = (imageData(idx) & 0xFF) / 255.0
    val g = (imageData(idx + 1) & 0xFF) / 255.0
    val b = (imageData(idx + 2) & 0xFF) / 255.0
    totalBrightness += (r + g + b) / 3.0
    pixelCount += 1
  }

  totalBrightness / pixelCount
}

def brightnessContrast(
  imageData: Array[Byte],
  width: Int,
  height: Int,
  region1: String,
  region2: String
): Double = {
  val brightness1 = regionBrightness(imageData, width, height, region1)
  val brightness2 = regionBrightness(imageData, width, height, region2)
  math.abs(brightness1 - brightness2) / math.max(brightness1, brightness2)
}
```

#### 5.2 Add Custom Matchers

**File:** `optix-jni/src/test/scala/menger/optix/ImageMatchers.scala`

```scala
def showShadows(width: Int, height: Int): Matcher[Array[Byte]] = {
  Matcher { imageData =>
    val contrast = brightnessContrast(imageData, width, height, "top-left", "bottom-right")
    MatchResult(
      contrast >= 0.3,
      s"Image should show shadows (contrast: $contrast, expected ≥0.3)",
      s"Image shows shadows (contrast: $contrast)"
    )
  }
}

def haveBrightnessContrast(
  minContrast: Double,
  width: Int,
  height: Int
): Matcher[Array[Byte]] = {
  Matcher { imageData =>
    val contrast = brightnessContrast(imageData, width, height, "top-left", "bottom-right")
    MatchResult(
      contrast >= minContrast,
      s"Brightness contrast $contrast should be at least $minContrast",
      s"Brightness contrast $contrast meets minimum $minContrast"
    )
  }
}
```

#### 5.3 Create Test File

**File:** `optix-jni/src/test/scala/menger/optix/ShadowRayTest.scala`

```scala
package menger.optix

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ShadowRayTest extends AnyFlatSpec with Matchers with RendererFixture:

  "Shadow rays" should "create darker regions when enabled" in:
    // Setup: Opaque sphere with light from top-left
    TestScenario.default()
      .withSphereColor(ColorConstants.OPAQUE_LIGHT_GRAY)
      .withLightDirection(Array(-1.0f, -1.0f, -1.0f))
      .applyTo(renderer)

    renderer.setShadows(true)

    val imageData = renderImage(800, 600)

    // Verify shadow appears in bottom-right region
    imageData should showShadows(800, 600)
    imageData should haveBrightnessContrast(0.3, 800, 600)

  it should "show uniform lighting when disabled" in:
    TestScenario.default()
      .withSphereColor(ColorConstants.OPAQUE_LIGHT_GRAY)
      .withLightDirection(Array(-1.0f, -1.0f, -1.0f))
      .applyTo(renderer)

    renderer.setShadows(false)

    val imageData = renderImage(800, 600)

    // Without shadows, brightness should be more uniform
    val contrast = ImageValidation.brightnessContrast(
      imageData, 800, 600, "top-left", "bottom-right"
    )
    contrast should be < 0.15

  it should "report shadow ray statistics" in:
    TestScenario.default()
      .withSphereColor(ColorConstants.OPAQUE_RED)
      .applyTo(renderer)

    renderer.setShadows(true)

    val result = renderer.render(800, 600)

    result.shadowRays should be > 0L
    result.shadowRays should be < result.primaryRays  // Not all pixels trace shadow rays

  it should "have acceptable performance overhead" in:
    TestScenario.default()
      .withSphereColor(ColorConstants.OPAQUE_GREEN)
      .applyTo(renderer)

    // Baseline without shadows
    renderer.setShadows(false)
    val startNoShadows = System.nanoTime()
    for (_ <- 1 to 10) renderer.render(800, 600)
    val timeNoShadows = (System.nanoTime() - startNoShadows) / 10

    // With shadows
    renderer.setShadows(true)
    val startWithShadows = System.nanoTime()
    for (_ <- 1 to 10) renderer.render(800, 600)
    val timeWithShadows = (System.nanoTime() - startWithShadows) / 10

    val overhead = (timeWithShadows - timeNoShadows).toDouble / timeNoShadows
    overhead should be < 0.30  // Less than 30% overhead
```

---

### Phase 6: Documentation (30 min)

#### 6.1 Update CLI Help

Already covered in `MengerCLIOptions.scala` with `descr` parameter.

#### 6.2 Update Enhancement Plan

**File:** `optix-jni/ENHANCEMENT_PLAN.md`

Update progress section:
```markdown
### Completed Features
- ✅ **Ray Statistics (1.1)** - Completed 2025-11-14 (6 hours)
- ✅ **Shadow Rays (1.2)** - Completed 2025-11-14 (X hours)

**Total time spent so far:** ~(18 + X) hours
```

Update Sprint 1 section:
```markdown
### Sprint 1: Foundation - COMPLETE
- ✅ Feature 1.1: Ray Statistics (COMPLETE - 6 hours)
- ✅ Feature 1.2: Shadow Rays (COMPLETE - X hours)
```

---

## Testing Checklist

Before marking feature complete, verify:

- [ ] **Unit test:** `setShadows(true/false)` changes params correctly
- [ ] **Visual test:** Shadow appears on plane under sphere
- [ ] **Brightness test:** Shadowed region ≥30% darker than lit region
- [ ] **Statistics test:** `shadow_rays` counter accurate
- [ ] **Performance test:** Overhead <30% vs baseline
- [ ] **CLI test:** `--shadows` flag works correctly
- [ ] **Validation test:** `--shadows` without `--optix` shows error
- [ ] **Integration test:** All 95+ existing tests still pass
- [ ] **No artifacts:** No shadow acne or peter-panning visible

---

## Acceptance Criteria

- ✅ Hard shadows visible on plane when sphere occludes light
- ✅ Shadow position geometrically correct
- ✅ `--shadows` flag toggles feature on/off
- ✅ No visual artifacts (shadow acne, peter-panning)
- ✅ Performance overhead <30%
- ✅ Automated tests verify shadow appearance
- ✅ Statistics report shadow ray count
- ✅ All existing tests continue to pass

---

## Risk Mitigation

### Self-Intersection (Shadow Acne)
**Risk:** Shadow rays hit the surface they originated from, causing artifacts.

**Mitigation:** Use `SHADOW_RAY_OFFSET` to move shadow ray origin slightly along normal.

### Performance Impact
**Risk:** Shadow rays double the tracing workload for lit surfaces.

**Mitigation:**
- Use `OPTIX_RAY_FLAG_TERMINATE_ON_FIRST_HIT` for early exit
- Only trace shadow rays when `diffuse > 0.0f` (surface faces light)
- Target <30% overhead

### Test Flakiness
**Risk:** Brightness thresholds may vary slightly between runs.

**Mitigation:**
- Use generous margins (30% contrast, not 50%)
- Average multiple renders for performance tests
- Test relative difference (with vs without) not absolute values

---

## Implementation Order

1. **Start with data structures** (quick win, enables compilation)
2. **Implement CUDA shader** (core functionality)
3. **Add C++/JNI bindings** (plumbing)
4. **Add Scala API** (interface layer)
5. **Write tests** (verify correctness)
6. **Run full test suite** (ensure no regressions)
7. **Update documentation** (record progress)

---

## Success Indicators

When complete, you should see:
- Shadow on plane beneath sphere (visual confirmation)
- `shadow_rays` count in statistics output
- Test output showing "Shadow rays" tests passing
- Performance within 30% of baseline

---

**Status:** Ready to implement - all prerequisites analyzed and understood.
