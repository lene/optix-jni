# AGENTS.md

Guidance for AI coding agents working in this repository.

This is a JNI bridge exposing NVIDIA OptiX ray tracing to JVM languages (Scala, Java, Kotlin). Two build modes: real CUDA/OptiX build (requires nvcc + OptiX SDK) and stub (no-op, builds anywhere). The stub exists for CI jobs that test non-GPU concerns; it must never be published to Maven Central.

---

## Critical rules

1. **Never commit directly to `main`.** Always work on a feature branch. Open a PR to merge into main. If on `main`, switch to (or create) a feature branch first.
2. **Never push without explicit user confirmation.** Commit locally, show the diff, wait for "push."
3. **Always monitor the CI pipeline after pushing.** Fix any failures.
4. **Never `git add -A`.** Add files explicitly.
5. **Never commit failing tests.**

---

## Branch and PR workflow

```bash
git checkout -b fix/short-description   # always branch from main
# ... make changes, commit ...
git push origin fix/short-description
gh pr create --title "..." --body "..."
```

CI runs on every PR. All jobs must pass before merging.

---

## Build modes

| Mode | Condition | Artifact |
|---|---|---|
| Real | nvcc on PATH, OptiX SDK present | `liboptixjni.so` (~1.2 MB) + `optix_shaders.ptx` (~3.3 MB) |
| Stub | No nvcc | `liboptixjni.so` (~14 KB stub), no PTX |

`build.sbt` aborts `sbt package` if PTX is absent (prevents stub publish). The stub is intentional for jobs like ArchUnit that test non-GPU concerns.

## CI runners

| Job | Runner | Needs CUDA? |
|---|---|---|
| scalafix, cppcheck, doc-completeness | ubuntu-latest | No |
| archunit, scala-tests, build-native, smoke-tests, publish | `[self-hosted, Linux, X64, nvidia]` | Yes |

All jobs requiring native compilation must run on the nvidia self-hosted runner with `Add CUDA to PATH` step.

---

## Common commands

```bash
sbt compile                        # All modules
sbt test                           # Scala + C++ tests
sbt "testOnly ClassName"           # Specific test
sbt nativeCompile                  # C++/CUDA native build
sbt package                        # Build jar (aborts if PTX absent)
sbt publishLocal                   # Local ivy publish (aborts if PTX absent)
```

Pipeline monitoring:
```bash
gh run list --limit 5
gh run view <run-id>
```
