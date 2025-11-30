# OptiX JNI Archive

This directory contains completed feature documentation and historical implementation artifacts for the OptiX JNI module.

## Index

### Completed Sprint Features

**Sprint 1.2 - Shadow Rays Implementation**
- `SHADOW_RAYS_PLAN.md` (14 KB) - Original shadow ray implementation plan
- `SHADOW_RAYS_SBT_FIX.md` (9.2 KB) - Shader Binding Table fixes for shadow rays
- `SHADOW_TESTING_NOTES.md` (11 KB) - Testing approach and coverage notes
- `SHADOW_TEST_COVERAGE.md` (7.7 KB) - Test validation and coverage details
- `TRANSPARENT_SHADOWS_IMPLEMENTATION.md` (7.9 KB) - Glass object shadow behavior

All shadow ray features are now complete and integrated into the main codebase.

### Architecture Refactoring

**Two-Layer Architecture (Completed)**
- `REFACTORING_TWO_LAYER_ARCHITECTURE.md` (21 KB) - Major refactoring that separated:
  - High-level OptiXWrapper API
  - Low-level OptiXContext implementation
  - Clean separation of concerns

This refactoring was completed and is now the current architecture.

### Testing & Debugging Tools

**Standalone C++ Test** (not in build):
- `standalone_test.cpp` - Valgrind memory leak detection test
  - Compile-only verification program
  - Useful for debugging memory issues outside of JNI

## Active OptiX JNI Documentation

For current documentation, see:
- **Module README**: `/optix-jni/README.md`
- **Sprint Planning**: `/optix-jni/ENHANCEMENT_PLAN.md` (master roadmap)
- **Current Sprints**:
  - `/optix-jni/SPRINT_5_PLAN.md` - Triangle Mesh Foundation
  - `/optix-jni/SPRINT_6_PLAN.md` - Cube Primitive
  - `/optix-jni/SPRINT_7_PLAN.md` - Multiple Objects
- **CI Configuration**: `/optix-jni/RUNNER_SETUP.md`
- **Release Process**: `/optix-jni/RELEASE_CHECKLIST.md`

## Sprint Timeline

| Sprint | Feature | Status | Archive Docs |
|--------|---------|--------|--------------|
| 1.1 | Ray Statistics | ✅ Complete | See `/docs/archive/` |
| 1.2 | Shadow Rays | ✅ Complete | This directory |
| 2.1 | Mouse Camera Control | ✅ Complete | (no dedicated docs) |
| 2.2 | Multiple Light Sources | ✅ Complete | (integrated in code) |
| 3.1 | Adaptive Antialiasing | ✅ Complete | (no dedicated docs) |
| 3.2 | Unified Color API | ✅ Complete | (no dedicated docs) |
| 3.3 | OptiX Cache Management | ✅ Complete | (no dedicated docs) |
| 4 | Caustics | ⏸️ Deferred | See `/docs/caustics/` |
| 5 | Triangle Mesh + Cube | ✅ Complete | Active docs in parent |
| 6 | Full Geometry Support | 📋 Planned | Active planning |
| 7 | Materials | 📋 Planned | Active planning |

## Using Archive Documents

### Shadow Ray Implementation Reference

If you need to understand shadow ray implementation:
1. Start with `SHADOW_RAYS_PLAN.md` for overall design
2. Check `SHADOW_RAYS_SBT_FIX.md` for SBT-specific issues
3. Review test coverage in `SHADOW_TEST_COVERAGE.md`
4. For glass shadows, see `TRANSPARENT_SHADOWS_IMPLEMENTATION.md`

### Architecture Reference

The two-layer architecture refactoring in `REFACTORING_TWO_LAYER_ARCHITECTURE.md` provides:
- Rationale for the current OptiXWrapper/OptiXContext split
- Migration strategy used
- Design patterns implemented

This is useful when:
- Adding new OptiX features (follow the established pattern)
- Understanding the API design philosophy
- Debugging API vs implementation issues

## Archive Organization

```
optix-jni/archive/
├── README.md (this file)
├── SHADOW_RAYS_PLAN.md
├── SHADOW_RAYS_SBT_FIX.md
├── SHADOW_TESTING_NOTES.md
├── SHADOW_TEST_COVERAGE.md
├── TRANSPARENT_SHADOWS_IMPLEMENTATION.md
├── REFACTORING_TWO_LAYER_ARCHITECTURE.md
└── standalone_test.cpp
```

---

*Last Updated: 2025-11-25*
