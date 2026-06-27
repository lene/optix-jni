# optix-jni 1.0 API Review — Sprint 30

**Date:** 2026-06-27
**Current version:** 0.1.5
**Target:** 1.0.0 — SemVer stability contract on the public API

## Scope

1.0 means "everything we chose to expose is stable," not "everything is wrapped."
The API surface is intentionally narrow: the `OptiXRenderer` facade, the standalone
`OptiXDenoiser`, domain value objects (`Material`, `Light`, `RayStats`), and the
low-level `NativeOptiXApi` for advanced users.

## Decisions by Class

### OptiXRenderer — KEEP (stable)

| Method/Trait | Decision | Rationale |
|-------------|----------|-----------|
| `OptiXRenderer` class | Keep | Core facade |
| `OptiXSphereApi` trait | Keep | Internal (`private[optix]`), no API change |
| `OptiXMeshApi` trait | Keep | Internal |
| `OptiXPlaneApi` trait | Keep | Internal |
| `OptiXTextureApi` trait | Keep | Internal |
| `OptiXRenderApi` trait | Keep | Internal |
| `nativeHandle: Long` | Keep (document) | JNI design; document as read-only externally |
| `isInitialized` | Keep | Now `private[optix]` (Sprint 30.4) |
| `initialize(maxInstances)` | Keep | |
| `reinitialize(maxInstances)` | Keep | |
| `dispose()` | Keep | |
| `setShadows(enabled)` | Keep | Add `isInitialized` guard |
| `setAntialiasing(enabled, maxDepth, threshold)` | Keep | |
| `setMaxRayDepth(depth)` | Keep | |
| `setCaustics(...)` | Keep | |
| `renderWithStats(width, height)` | Keep | |
| `removeInstance(instanceId)` | Keep | |
| `clearAllInstances()` | Keep | |
| `updateImageDimensions(w, h)` | Keep | |

### Legacy methods — DEPRECATE (keep for 1.0, remove in 2.0)

| Method | Decision | Rationale |
|--------|----------|-----------|
| `setIOR(ior: Float)` | Deprecate | Legacy single-sphere API. Use `Material` + IAS path. |
| `setScale(scale: Float)` | Deprecate | Legacy single-sphere scale. |
| `setSphereColor(color)` | Deprecate (OptiXSphereApi) | Legacy path. |
| `setTriangleMeshIOR(ior)` | Deprecate | Legacy single-mesh IOR. Use `addTriangleMeshInstance(material)`. |
| `setTriangleMeshColor(color)` | Deprecate (OptiXMeshApi) | Legacy. |
| `clearTriangleMesh()` | Deprecate | Legacy. `clearAllInstances()` replaces it. |
| `hasTriangleMesh()` | Deprecate | Legacy. |
| `setLight(direction, intensity)` | Deprecate | Legacy single-light. Use `setLights(Array[Light])`. |

### Value objects — KEEP

| Type | Decision |
|------|----------|
| `Light` | Keep — JNI light payload |
| `RayStats` | Keep — frame statistics |
| `CausticsStats` | Keep — PPM caustics counters |
| `RenderResult` | Keep — render output + stats |
| `PlaneSpec` | Keep — plane specification |
| `CheckerPattern` | Keep — checker colors |
| `AreaLightShape` | Keep — DISK constant |
| `Material` | Keep — material descriptor |

### Standalone denoiser — KEEP

| Type | Decision |
|------|----------|
| `OptiXDenoiser` | Keep |
| `DenoiseImage` | Keep |
| `DenoiseGuides` | Keep |

### Low-level API — KEEP (document as advanced)

| Type | Decision |
|------|----------|
| `NativeOptiXApi` | Keep — `private` visibility appropriate. Document as "unstable for now" if further OptiX features want to use different wrapping patterns. |

### Types in `menger.common` (dependency) — NO CHANGE

`Color`, `Vector`, `ImageSize`, `Material`, `RenderConfig`, `CausticsConfig`, `TriangleMeshData`, `Const`, `Light` (common), `ObjectType` — all in menger-common, not optix-jni's surface.

---

## Null-unsafe return types — FIX for 1.0

| Method | Current | Fix |
|--------|---------|-----|
| `render(size: ImageSize): Array[Byte]` | Returns `null` on failure | Already wrapped via `renderWithStats` → `Optional`. Keep as-is with documented nullability. |
| `renderWithStats(size): Optional[RenderResult]` | Returns `Optional.empty()` on failure | ✅ Already correct |
| `renderWithStats(width, height): RenderResult` (`@native`) | Returns `null` on native failure | Document. Internal JNI surface. |
| `getCausticsStats: CausticsStats` | Returns `null` when disabled | ✅ Documented |

## Summary

| Category | Count | Action |
|----------|-------|--------|
| Stable public API | 8 classes, 5 traits | Keep as-is |
| Deprecate (keep for 1.0) | 8 methods | Add `@deprecated` annotation with migration hint |
| Null safety | 1 public method | Document as nullable |
| Remove before 1.0 | 0 methods | No blocking removals |

---

*Generated for Sprint 30, Task 30.5. This document defines the 1.0 API contract. After 1.0, SemVer rules apply: no breaking changes without a major version bump.*
