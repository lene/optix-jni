# optix-jni 1.0.0 Release Checklist

**Milestone:** First stable SemVer release.
**Pre-1.0 contract:** API may change between minor versions.
**1.0 contract:** SemVer stability on `OptiXRenderer`, `NativeOptiXApi`, and all public traits.

---

## Pre-Release Gates

- [ ] **MiMa binary compatibility** — `sbt mimaReportBinaryIssues` passes against 0.1.5 baseline
- [ ] **Scaladoc completeness** — `scripts/check-doc-completeness.sh` passes on all public API files
- [ ] **API review** — `docs/api-review-1.0.md` decisions honored (deprecations applied, no blocking removals)
- [ ] **All tests pass** — `sbt test` (Scala + C++ Google Test)
- [ ] **ArchUnit rules pass** — `sbt "testOnly *ArchUnit*"`
- [ ] **Pre-push hook green** — `.git_hooks/pre-push` (compile, test, scalafix, cppcheck, coverage)
- [ ] **Coverage ≥80%** — `sbt coverage test coverageReport`
- [ ] **No `@deprecated` without migration path** — every deprecated method has a comment pointing to the replacement

## Version Bump Steps

1. [ ] Update `version := "1.0.0"` in `build.sbt`
2. [ ] Update `CHANGELOG.md` with 1.0.0 release notes (breaking changes, deprecations, new features since 0.1.5)
3. [ ] Verify `ROADMAP.md` in menger repo reflects optix-jni 1.0 status
4. [ ] Tag: `git tag -a 1.0.0 -m "optix-jni 1.0.0 — Stable API with SemVer contract"`
5. [ ] Push tag: `git push origin 1.0.0`

## Post-Release Verification

- [ ] GitHub Actions tag pipeline triggers publish to Maven Central
- [ ] `io.github.lene:optix-jni:1.0.0` resolves on Maven Central
- [ ] menger project bumps optix-jni dependency to 1.0.0 and passes all tests
- [ ] Install smoke test: `sbt publishLocal` + fresh project compiles against 1.0.0

## Post-1.0 Rules (enforced from 1.0.0 onward)

- [ ] MiMa failures **block release** (remove `mimaBinaryIssueFilters` workarounds)
- [ ] No `@deprecated` addition without a replacement path in the same release
- [ ] Breaking changes require major version bump (2.0, 3.0, etc.)
- [ ] New public API must have Scaladoc in the same PR

---

## API Surface Summary (for release notes)

**Stable (1.0):**
- `OptiXRenderer` — high-level renderer facade with IAS multi-object support
- `OptiXDenoiser` — standalone HDR denoiser with guide AOVs
- `Material` — PBR material descriptor (color, IOR, roughness, metallic, etc.)
- `Light` — JNI light payload (directional, point, area)
- `RayStats`, `CausticsStats`, `RenderResult` — statistics and output types
- `PlaneSpec`, `CheckerPattern` — plane configuration
- `AreaLightShape` — area light shape IDs
- `NativeOptiXApi` — low-level OptiX context/program/pipeline API (advanced users)

**Deprecated (keep for 1.0, remove in 2.0):**
- Legacy single-object path: `setIOR`, `setScale`, `setSphereColor`, `setTriangleMeshIOR`, `setTriangleMeshColor`, `clearTriangleMesh`, `hasTriangleMesh`, `setLight(direction, intensity)`
- Use the IAS multi-object path with `Material` instead

**New in 1.0 (since 0.1.5):**
- Validation mode (`MENGER_OPTIX_VALIDATION=1`)
- Shader execution reordering (`MENGER_OPTIX_SER=1`, Ada+ GPUs only)
- `isInitialized` guards on denoising/accumulation API
- Per-frame GPU buffer re-allocation optimization
- MiMa binary compatibility enforcement

---

*Generated Sprint 30, Task 30.5. This checklist gates the 1.0.0 release.*
