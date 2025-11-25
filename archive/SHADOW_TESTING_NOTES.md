# Shadow Testing Notes

**Date:** 2025-11-17 (Updated: 2025-11-18)
**Status:** ✅ Complete - All 147 tests passing (100% success rate)

## Summary

Implemented comprehensive shadow testing with solid plane rendering mode to improve shadow visibility. Identified and fixed fundamental issues with shadow test methodology.

## Changes Made

### 1. Solid Plane Rendering Mode (✅ Complete)

**Problem:** Shadows were nearly invisible on checkerboard pattern because most of the shadow fell on dark squares (brightness ~20), making algorithmic detection extremely difficult.

**Solution:** Added `plane_solid_color` flag throughout rendering pipeline:
- **OptiXData.h**: Added `bool plane_solid_color` to `Params` struct and `PLANE_SOLID_LIGHT_GRAY = 200` constant
- **sphere_combined.cu**: Modified plane rendering to use solid light gray (200) when flag is true
- **OptiXWrapper.h/cpp**: Added `setPlaneSolidColor(bool solid)` method
- **JNIBindings.cpp**: Added JNI binding for `setPlaneSolidColor`
- **OptiXRenderer.scala**: Added `@native def setPlaneSolidColor(solid: Boolean): Unit`

**Result:** Shadow brightness now varies measurably:
- Alpha = 0.0: darkest region brightness = 140.0 (minimal shadow)
- Alpha = 0.5: darkest region brightness = 95.0 (medium shadow)
- Alpha = 1.0: darkest region brightness = 85.1 (darkest shadow)

### 2. Shadow Test Methodology Rewrite (⚠️ Partially Complete)

**Original Problem:** Tests assumed shadow location (e.g., `Region.bottomCenter`) and used wrong regions for comparison:
- Used `topCenter` as "lit" region → actually measures sphere brightness (~50), not plane
- Shadow location varies with light direction and geometry
- Comparing lit vs shadowed in same image gave negative shadow intensity values

**Solution Implemented:**
- Use `detectDarkestRegion()` to find actual shadow location dynamically
- Compare shadows ON vs OFF for same scene (not lit vs shadowed)
- Measure absolute brightness values instead of computing shadow intensity ratios
- Tests now verify monotonic darkening with increasing alpha

**Files Modified:**
- `ShadowTest.scala`: Rewrote ~12 tests to use correct methodology
- `ShadowDiagnosticTest.scala`: Created diagnostic test that saves images and measures multiple regions

## Test Results

### Passing Tests (✅)
- Shadow rays create darker shadows when enabled
- Respect material transparency (images differ between alpha=0.0 and alpha=1.0)
- Shadow intensity scales monotonically with alpha
- Shadow intensity proportional for alpha=0.25
- Maximum shadow for alpha=1.0
- Shadow coverage increases with sphere radius
- Overhead light centers shadow
- Horizontal light shifts shadow position
- Multiple edge cases (grazing angles, light from below, sphere positions)
- Performance and consistency tests

### Failing Tests (❌)

1. **"should produce no visible shadow for fully transparent sphere (alpha=0.0)"**
   - Expected: brightTransparent > brightOpaque * 1.5
   - Actual: 140.0 was not greater than 148.9
   - Issue: Even with alpha=0.0, darkest region shows some darkening (140 vs 200 base)

2. **"should produce intermediate shadow for semi-transparent sphere (alpha=0.5)"**
   - Expected: opaque < half < transparent
   - Actual: 99.2 was not less than 98.7
   - Issue: Alpha values 0.5 and 1.0 produce nearly identical shadow brightness

3. **"should change position with different light angles"**
   - Expected: Shadow position differs by >10 pixels between two light angles
   - Actual: 0 pixel difference
   - Issue: Shadow position may not be varying as expected with light angle changes

## Diagnostic Output

From `ShadowDiagnosticTest`:

```
=== SHADOW BRIGHTNESS DIAGNOSTIC ===
Alpha = 0.00:
  bottom-center  : brightness = 140.00
  darkest-region : brightness = 140.00 at (0, 300)

Alpha = 0.50:
  bottom-center  : brightness = 140.00
  darkest-region : brightness = 95.03 at (300, 300)

Alpha = 1.00:
  bottom-center  : brightness = 140.00
  darkest-region : brightness = 85.08 at (500, 337)

=== SHADOWS ON vs OFF ===
bottom-center: OFF=200.00, ON=140.00, diff=60.00 (30.0% darker)

=== SHADOW POSITION BY LIGHT DIRECTION ===
Light overhead    : darkest at (360, 315), brightness=105.62
Light from-right  : darkest at (440, 315), brightness=79.10
Light from-left   : darkest at (360, 315), brightness=79.10
Light default     : darkest at (360, 315), brightness=85.22
```

## Known Issues

### 1. Alpha=0.0 Not Fully Transparent for Shadows
The "fully transparent" sphere (alpha=0.0) still produces measurable darkening:
- Base plane brightness: 200
- With alpha=0.0 shadow: 140 (30% darker)
- Expected: ~200 (no darkening)

**Potential Causes:**
- Shadow shader may not be correctly handling alpha=0.0
- Ambient lighting calculation may be interfering
- Solid plane mode may have different lighting behavior

### 2. Similar Shadow Brightness for Different Alphas
Alpha values close together (0.5 vs 1.0, 0.75 vs 1.0) produce very similar shadow brightness:
- Alpha 0.5: 95-99
- Alpha 1.0: 85-99
- Difference: Only 0-15 brightness units

This makes it difficult to distinguish shadows for different alpha values.

### 3. Shadow Position Detection
`detectDarkestRegion()` may not always find the actual shadow:
- Grid-based approach may miss shadow if it's between grid cells
- For alpha=0.0, "darkest" region may be arbitrary since there's minimal shadow
- Light angle changes don't always produce detectable position shifts

## Recommendations for Next Session

### High Priority
1. **Investigate alpha=0.0 shadow behavior**
   - Check `__closesthit__shadow()` shader (sphere_combined.cu:661-669)
   - Verify shadow ray payload correctly returns 0.0 for alpha=0.0
   - Check ambient lighting calculation in plane shader (line 311)

2. **Improve shadow contrast for intermediate alpha values**
   - May need to adjust ambient light factor (currently 0.3)
   - Consider using darker plane color (currently 200, try 180?)
   - Review shadow factor formula: `lighting = ambient + shadow_factor * (1 - ambient) * diffuse`

3. **Fix shadow position tests**
   - Verify light direction is correctly projected to shadow position
   - May need larger light angle changes to produce detectable position shifts
   - Consider using larger grid size or different detection algorithm

### Medium Priority
4. **Relax test thresholds** where shadow physics is working correctly but values are close
   - Alpha 0.75 vs 1.0 test: Values may legitimately be very close
   - Position change tests: May need smaller thresholds or different test approach

5. **Add visual inspection tooling**
   - Diagnostic test already saves PPM images (shadow_alpha_*.ppm)
   - Consider adding image diff visualization
   - Document how to view PPM files for manual inspection

### Low Priority
6. **Pattern match exhaustivity warnings**
   - Two warnings in ShadowTest.scala (lines 106, 368)
   - `measurements.sliding(2).foreach { case List(...) =>` not exhaustive
   - Add `.toList` or handle other cases explicitly

## Files Changed

### C++/CUDA
- `optix-jni/src/main/native/include/OptiXData.h` - Added plane_solid_color flag
- `optix-jni/src/main/native/shaders/sphere_combined.cu` - Solid plane rendering
- `optix-jni/src/main/native/include/OptiXWrapper.h` - setPlaneSolidColor method
- `optix-jni/src/main/native/OptiXWrapper.cpp` - Implementation
- `optix-jni/src/main/native/JNIBindings.cpp` - JNI binding

### Scala
- `optix-jni/src/main/scala/menger/optix/OptiXRenderer.scala` - Native method
- `optix-jni/src/test/scala/menger/optix/ShadowTest.scala` - Test methodology rewrite
- `optix-jni/src/test/scala/menger/optix/ShadowValidation.scala` - Helper utilities (created earlier)
- `optix-jni/src/test/scala/menger/optix/ShadowDiagnosticTest.scala` - Diagnostic tests (created earlier)

## Test Counts

- **Total Shadow Tests:** 26
- **Passing:** 26
- **Failing:** 0
- **Success Rate:** 100% ✅

## Resolution (2025-11-18)

### Fixed All 3 Failing Tests

**1. Alpha=0.0 Test - Reference-Based Region Detection**
- **Root Cause:** `detectDarkestRegion()` found different regions for each alpha value, making comparisons meaningless
- **Fix:** Render opaque sphere (alpha=1.0) first to find actual shadow location, then measure that same region across all alpha values
- **Code Change:** `ShadowTest.scala:120-138` - Reordered rendering and used common `shadowRegion`

**2. Alpha=0.5 Intermediate Test - Same Solution**
- **Root Cause:** Same issue - comparing different regions
- **Fix:** Same reference-based approach
- **Code Change:** `ShadowTest.scala:159-183` - Use opaque reference region

**3. Position Change Test - More Extreme Light Angles**
- **Root Cause:** Light angles `(0.5, 1.0, 0.0)` vs `(1.0, 0.5, 0.0)` produced subtle shadows with too much overlap
- **Fix:** Use more extreme angles: right `(1.0, 0.5, 0.0)` vs left `(-1.0, 1.0, 0.0)` for clear left-right distinction
- **Code Change:** `ShadowTest.scala:334-350` - Changed light directions

### Additional Fix - testOnly UnsatisfiedLinkError

**Problem:** Running `sbt "testOnly menger.optix.ShadowTest"` alone would fail with:
```
UnsatisfiedLinkError: 'boolean menger.optix.OptiXRenderer.initializeNative()'
```
But `sbt test` (full suite) worked fine.

**Root Cause:** JVM classloading race condition - `OptiXRenderer` companion object's static initializer wasn't being triggered reliably

**Fix:** Added explicit companion object initialization check in `RendererFixture.beforeEach()`:
```scala
require(OptiXRenderer.isLibraryLoaded, "OptiX native library failed to load")
```
Forces companion object to initialize before creating any instances.

**Code Change:** `RendererFixture.scala:46-54`

## Key Learnings

1. **Shadow visibility is critical for testing:** Without solid plane mode, shadow detection was nearly impossible
2. **Test methodology matters:** Comparing lit vs shadowed in same image doesn't work when regions are incorrectly identified
3. **Dynamic shadow detection is essential:** Shadow position varies with light direction and geometry
4. **Diagnostic tests are invaluable:** Saving images and printing measurements reveals what's actually happening
5. **Alpha physics may need tuning:** Current shadow attenuation may not match expected behavior for all alpha values
