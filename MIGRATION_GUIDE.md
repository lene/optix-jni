# OptiX JNI Test Migration Guide

This guide shows how to migrate existing OptiX JNI tests to use the new refactored utilities.

## Quick Reference

### Step 1: Add RendererFixture to your test class

**Before:**
```scala
class MyTest extends AnyFlatSpec with Matchers with LazyLogging:
  // No automatic renderer management
```

**After:**
```scala
class MyTest extends AnyFlatSpec with Matchers with LazyLogging with RendererFixture:
  // renderer automatically initialized before each test, disposed after
```

### Step 2: Remove manual renderer initialization

**Before:**
```scala
"my test" should "do something" in:
  val renderer = new OptiXRenderer
  renderer.initialize()
  renderer.setCamera(TestConfig.DefaultCameraEye, ...)
  renderer.setLight(TestConfig.DefaultLightDirection, ...)
  renderer.setSphere(0.0f, 0.0f, 0.0f, 0.5f)
  // ... test logic ...
  renderer.dispose()
```

**After:**
```scala
"my test" should "do something" in:
  // renderer already available and initialized
  // ... test logic ...
  // disposal automatic
```

### Step 3: Replace magic constants with named constants

**Before:**
```scala
renderer.setSphereColor(0.784f, 0.902f, 1.0f, 0.902f)  // What is this?
area should be < 500  // Why 500?
```

**After:**
```scala
import ColorConstants._
import ThresholdConstants._

renderer.setSphereColor(GLASS_LIGHT_CYAN._1, GLASS_LIGHT_CYAN._2,
  GLASS_LIGHT_CYAN._3, GLASS_LIGHT_CYAN._4)
area should be < SMALL_SPHERE_MAX_AREA
```

### Step 4: Use TestScenario for configuration

**Before:**
```scala
renderer.setCamera(...)
renderer.setLight(...)
renderer.setSphere(0.0f, 0.0f, 0.0f, 0.5f)
renderer.setSphereColor(0.0f, 1.0f, 0.0f, 1.0f)
renderer.setIOR(1.5f)
renderer.setScale(1.0f)
```

**After:**
```scala
TestScenario.glassSphere()
  .withSphereColor(OPAQUE_GREEN)
  .applyTo(renderer)
```

### Step 5: Use custom matchers for assertions

**Before:**
```scala
val (centerX, centerY) = ImageValidation.detectSphereCenter(imageData, width, height)
centerX should (be >= width / 2 - 30 and be <= width / 2 + 30)
centerY should (be >= height / 2 - 30 and be <= height / 2 + 30)
```

**After:**
```scala
import ImageMatchers._

imageData should haveSphereCenteredInImage(width, height)
```

## Complete Example Migration

### Original Test (OptiXGeometryTest.scala)

```scala
package menger.optix

import com.typesafe.scalalogging.LazyLogging
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import OptiXRendererTest.TestConfig

class OptiXGeometryTest extends AnyFlatSpec with Matchers with LazyLogging:

  OptiXRenderer.isLibraryLoaded shouldBe true

  "Sphere geometry" should "be centered in image" in:
    val renderer = new OptiXRenderer
    renderer.initialize()
    renderer.setCamera(
      TestConfig.DefaultCameraEye,
      TestConfig.DefaultCameraLookAt,
      TestConfig.DefaultCameraUp,
      TestConfig.DefaultCameraFov
    )
    renderer.setLight(TestConfig.DefaultLightDirection, TestConfig.DefaultLightIntensity)
    renderer.setSphere(0.0f, 0.0f, 0.0f, 0.5f)
    renderer.setSphereColor(0.0f, 1.0f, 0.0f, 1.0f)
    renderer.setIOR(1.0f)
    renderer.setScale(1.0f)

    val imageData = renderer.render(TestConfig.TestImageSize._1, TestConfig.TestImageSize._2)

    val (cx, cy) = ImageValidation.detectSphereCenter(
      imageData,
      TestConfig.TestImageSize._1,
      TestConfig.TestImageSize._2
    )

    cx should (be >= TestConfig.TestImageSize._1 / 2 - 30 and
      be <= TestConfig.TestImageSize._1 / 2 + 30)
    cy should (be >= TestConfig.TestImageSize._2 / 2 - 30 and
      be <= TestConfig.TestImageSize._2 / 2 + 30)

    renderer.dispose()
```

### Refactored Test

```scala
package menger.optix

import com.typesafe.scalalogging.LazyLogging
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import ColorConstants._
import ThresholdConstants._
import ImageMatchers._

class OptiXGeometryTestRefactored extends AnyFlatSpec
    with Matchers
    with LazyLogging
    with RendererFixture:

  OptiXRenderer.isLibraryLoaded shouldBe true

  "Sphere geometry (refactored)" should "be centered in image" in:
    TestScenario.default().applyTo(renderer)

    val imageData = renderer.render(TEST_IMAGE_SIZE._1, TEST_IMAGE_SIZE._2)

    imageData should haveSphereCenteredInImage(TEST_IMAGE_SIZE._1, TEST_IMAGE_SIZE._2)
```

**Line count reduction:** 50 lines → 18 lines (64% reduction)

## Available Utilities

### RendererFixture

Provides:
- `renderer: OptiXRenderer` - Automatically initialized/disposed
- `setCameraEye(x, y, z)` - Helper for camera position
- `setSphereColor(r, g, b, a)` - Helper with clear parameter names

### ColorConstants

Pre-defined colors:
- Primary: `OPAQUE_RED`, `OPAQUE_GREEN`, `OPAQUE_BLUE`, `OPAQUE_WHITE`
- Transparency: `SEMI_TRANSPARENT_WHITE`, `FULLY_TRANSPARENT_GREEN`
- Materials: `GLASS_LIGHT_CYAN`, `CLEAR_GLASS_WHITE`
- Performance: `PERFORMANCE_TEST_WHITE`, `PERFORMANCE_TEST_GREEN_CYAN`

Helper methods:
- `rgba(r, g, b, a)` - Create custom color
- `fromBytes(r, g, b, a)` - Convert from 0-255 range
- `withAlpha(baseColor, alpha)` - Modify alpha of existing color

### ThresholdConstants

Pre-defined thresholds:
- Performance: `MIN_FPS`
- Areas: `SMALL_SPHERE_MAX_AREA`, `MEDIUM_SPHERE_MIN_AREA`, etc.
- Position: `SPHERE_CENTER_TOLERANCE`
- Brightness: `MIN_GLASS_REFRACTION_STDDEV`, `MIN_WATER_REFRACTION_STDDEV`
- Image sizes: `TEST_IMAGE_SIZE`, `STANDARD_IMAGE_SIZE`

### TestScenario

Pre-configured scenarios:
- `TestScenario.glassSphere()` - IOR=1.5, light cyan tint
- `TestScenario.waterSphere()` - IOR=1.33, clear
- `TestScenario.diamondSphere()` - IOR=2.42, clear
- `TestScenario.opaqueSphere()`, `semiTransparentSphere()`, etc.
- `TestScenario.smallSphere()`, `largeSphere()`, etc.

Fluent API:
```scala
TestScenario.glassSphere()
  .withSphereRadius(1.0f)
  .withCameraDistance(5.0f)
  .withIOR(1.6f)
  .withPlane(1, false, -2.0f)
  .applyTo(renderer)
```

### ImageMatchers

Custom assertions:
- Geometry: `haveSphereCenterAt(x, y, width, height)`, `haveSphereCenteredInImage(width, height)`
- Area: `haveSmallSphereArea(width, height)`, `haveMediumSphereArea(width, height)`
- Color: `beRedDominant(width, height)`, `beGreenDominant(width, height)`
- Optics: `showGlassRefraction(width, height)`, `showWaterRefraction(width, height)`
- Background: `showBackground(width, height)`, `showPlaneInRegion("bottom", width, height)`

### TestUtilities

Helper functions:
- `savePPM(filename, data, width, height)` - Returns Try[File]
- `savePNG(filename, data, width, height)` - Returns Try[File]
- `withRenderer(scenario)(test)` - Automatic lifecycle management

### ImageValidationFunctional

Functional versions of image validation methods (zero vars, pure functional):
- `spherePixelArea(imageData, width, height)`
- `detectSphereCenter(imageData, width, height)`
- `edgeBrightness(imageData, width, height)`
- `colorChannelRatio(imageData, width, height)`
- All other ImageValidation methods

## Migration Checklist

For each test file:

- [ ] Add `with RendererFixture` to class declaration
- [ ] Remove manual `new OptiXRenderer`, `initialize()`, `dispose()` calls
- [ ] Import `ColorConstants._`, `ThresholdConstants._`, `ImageMatchers._`
- [ ] Replace magic color values with `ColorConstants`
- [ ] Replace magic thresholds with `ThresholdConstants`
- [ ] Use `TestScenario` for common configurations
- [ ] Replace manual assertions with custom `ImageMatchers`
- [ ] Replace `TestConfig.TestImageSize` with `TEST_IMAGE_SIZE`
- [ ] Test that all tests still pass
- [ ] Compare line counts (should see ~50-70% reduction)

## Common Patterns

### Performance Tests

**Before:**
```scala
val frames = 100
val start = System.nanoTime
for _ <- 0 until frames do renderer.render(width, height)
val duration = (System.nanoTime - start) / 1e9
val fps = frames / duration
fps should be > 10.0
```

**After:**
```scala
private def measureFPS(scenario: TestScenario, frames: Int = 100): Double =
  scenario.applyTo(renderer)
  val start = System.nanoTime
  (0 until frames).foreach(_ => renderer.render(TEST_IMAGE_SIZE._1, TEST_IMAGE_SIZE._2))
  (frames / ((System.nanoTime - start) / 1e9))

// In test:
val fps = measureFPS(TestScenario.glassSphere())
fps should be > MIN_FPS
```

### Multiple Renderers (Current Limitation)

Some tests use multiple renderer instances due to SBT update limitations. Keep this pattern:

```scala
"test" should "compare two configurations" in:
  val renderer1 = new OptiXRenderer
  renderer1.initialize()
  // ... config 1 ...
  val image1 = renderer1.render(...)
  renderer1.dispose()

  val renderer2 = new OptiXRenderer
  renderer2.initialize()
  // ... config 2 ...
  val image2 = renderer2.render(...)
  renderer2.dispose()

  // ... compare images ...
```

Don't try to use RendererFixture for tests that need multiple renderers.

### Alpha Variations

**Before:**
```scala
val alphas = Seq(0, 64, 128, 191, 255)
val results = alphas.map { alpha =>
  renderer.setSphereColor(0.0f, 1.0f, 0.0f, alpha / 255.0f)
  val imageData = renderer.render(width, height)
  ImageValidation.spherePixelArea(imageData, width, height)
}
```

**After:**
```scala
val alphas = Seq(
  FULLY_TRANSPARENT_GREEN,
  MOSTLY_TRANSPARENT_GREEN,
  SEMI_TRANSPARENT_GREEN,
  MOSTLY_OPAQUE_GREEN,
  OPAQUE_GREEN
)
val results = alphas.map { color =>
  setSphereColor(color._1, color._2, color._3, color._4)
  val imageData = renderer.render(TEST_IMAGE_SIZE._1, TEST_IMAGE_SIZE._2)
  ImageValidation.spherePixelArea(imageData, TEST_IMAGE_SIZE._1, TEST_IMAGE_SIZE._2)
}
```

## Testing Your Migration

After migrating a test file:

1. **Run the refactored tests:**
   ```bash
   sbt "project optixJni" "testOnly menger.optix.YourTestRefactored"
   ```

2. **Compare with original:**
   ```bash
   sbt "project optixJni" "testOnly menger.optix.YourTest"
   ```

3. **Both should pass with same test count**

4. **Check line count reduction:**
   ```bash
   wc -l YourTest.scala YourTestRefactored.scala
   ```

5. **Run full test suite:**
   ```bash
   sbt "project optixJni" test
   ```

## Next Files to Migrate

Recommended order:

1. **OptiXGeometryTest.scala** - Simple, good practice
2. **OptiXCameraTest.scala** - Similar to geometry tests
3. **OptiXPlaneTest.scala** - Simple plane visibility tests
4. **OptiXMaterialTest.scala** - Uses material scenarios
5. **OptiXAbsorptionTest.scala** - Beer-Lambert specific
6. **OptiXRefractionTest.scala** - More complex, uses custom matchers well
7. **OptiXRendererTest.scala** - Largest file, save for last, may split into multiple

## Questions?

See `REFACTORING_SUMMARY.md` for detailed documentation and examples.

For issues or questions, check existing refactored tests:
- `OptiXOpaqueSphereTestRefactored.scala` - Basic patterns
- `OptiXTransparencyTestRefactored.scala` - Alpha variations
- `OptiXPerformanceTestRefactored.scala` - Performance testing with helper methods
