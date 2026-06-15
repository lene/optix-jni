# optix-jni API Boundary

This document classifies the current `OptiXRenderer` public API surface into three
categories. New features should land on the correct side of this boundary — general
OptiX operations belong in optix-jni; Menger-specific scene-description logic belongs
in menger-app.

## General (OptiX renderer operations)

These methods express concepts that any OptiX-based renderer would need. They have no
inherent tie to Menger's sphere-sponge domain and should remain stable as the scene
model evolves.

| Method | Notes |
|--------|-------|
| `initialize(maxInstances)` | Allocates the native OptiX context |
| `dispose()` | Releases all native resources |
| `reinitialize(newMaxInstances)` | Replaces the context without external recreation |
| `isAvailable` / `ensureAvailable()` | Library-availability guard |
| `setCamera(eye, lookAt, up, fovDeg)` | Standard perspective camera |
| `updateImageDimensions(width, height)` | Resize output buffer |
| `setLight(direction, intensity)` | Legacy single directional light |
| `setLights(lights)` | Multi-light path (directional / point / area) |
| `setShadows(enabled)` | Hard shadow toggle |
| `setTransparentShadows(enabled)` | Coloured shadow toggle |
| `setBackgroundColor(r, g, b)` | Scene background |
| `setAntialiasing(enabled, maxDepth, threshold)` | AA parameters |
| `setDenoisingEnabled(enabled)` / `isDenoisingEnabled` | Opt-in OptiX HDR denoiser |
| `setCaustics(…)` / `enableCaustics` / `disableCaustics` | Progressive photon mapping |
| `getCausticsStats` | Caustics diagnostic statistics |
| `setRenderConfig(config)` | Batch-apply `RenderConfig` |
| `setCausticsConfig(config)` | Batch-apply `CausticsConfig` |
| `render(width, height)` / `render(size)` | Render to PNG bytes |
| `renderWithStats(…)` | Render and return `RenderResult` with ray counters |
| `clearAllInstances()` / `getInstanceCount()` | IAS lifecycle |
| `removeInstance(id)` | Remove one instance from the IAS |
| `isIASMode()` / `setIASMode(enabled)` | Switch between single-object and IAS mode |
| `clearPlanes()` / `addPlane*(…)` | Infinite ground/wall planes (general primitive) |
| `uploadTexture(…)` / `releaseTextures()` | GPU texture management |

## Menger-specific (sphere sponge domain)

These methods exist specifically to support the Menger sponge scene model. They encode
Menger-domain parameters (a single globally-shared sphere with a single color/IOR) that
have no meaning outside this project.

| Method | Notes |
|--------|-------|
| `setSphere(center, radius)` | Sets the one global sphere object |
| `setSphereColor(color)` | Color of that global sphere |
| `setIOR(ior)` | Index of refraction of the global sphere |
| `setScale(scale)` | Menger scale factor (affects sponge density) |

These belong in optix-jni for historical reasons (they were JNI-bootstrapped first) but
semantically they are menger-app concerns. A future refactor could move them to a
`MengerRenderer` subclass or adapter in menger-app.

## To generalize

These methods express general rendering concepts but currently carry Menger-specific
parameter shapes that limit reuse.

| Method | Current form | Generalised form |
|--------|-------------|-----------------|
| `addSphereInstance(transform, material)` | Sphere primitive in IAS, already general | Already general — no action needed |
| `addTriangleMeshInstance(…)` / `setTriangleMesh(mesh)` | `TriangleMeshData` is a common type, but `setTriangleMesh` still operates on the single-object legacy path | Split legacy single-mesh path from IAS path; expose `addMeshInstance` as the canonical form |
| `addCylinderInstance(p0, p1, radius, material)` | Already general | Already general — no action needed |
| `addPlane*(axis, positive, value, …)` | Axis-aligned infinite planes only | Could expose an arbitrary-plane form for non-axis-aligned floors |

## Decision rule for future features

> **Does this concept exist in any OptiX tutorial / path tracer?**
> - Yes → implement in optix-jni.
> - No, it only makes sense for sphere-sponge scenes → implement in menger-app (DSL or engine layer) and call the general optix-jni primitives from there.

The DSL scene graph (`SceneNode`, `Transform`, `Placement`) and the scene-to-renderer
bridge (`SceneConverter`, engine traits) are the natural home for Menger-specific
composition logic. The JNI layer should remain a thin, domain-agnostic wrapper around
the OptiX C++ API.
