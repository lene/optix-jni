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

## Git hooks

Hooks live in `.git_hooks/` (tracked). On a fresh clone, activate them with:

```bash
git config core.hooksPath .git_hooks
```

| Hook | When | Checks |
|------|------|--------|
| pre-commit | on `git commit` | compile, tests (GPU tests skip via `assume()` if no GPU), scalafix |
| pre-push | on `git push` | compile, tests, scalafix, cppcheck (if installed) |

CUDA warning on non-GPU machine is expected and non-fatal. Publishing stubs is blocked by `Compile/packageBin` in `build.sbt`.

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

`build.sbt` aborts `sbt package` if PTX is absent (prevents stub publish).

## CI runners

| Job | Runner | Needs CUDA? |
|---|---|---|
| scalafix, cppcheck, doc-completeness | ubuntu-latest | No |
| archunit, scala-tests, build-native, smoke-tests, publish | `[self-hosted, Linux, X64, nvidia]` | Yes |

All jobs requiring native compilation must run on the nvidia self-hosted runner with `Add CUDA to PATH` step.

---

## Maven Central incident protocol

Artifacts on Maven Central are **permanent — cannot be deleted**. If a defective artifact is published:
1. Open an issue documenting the defect
2. Fix the defect on a branch, bump patch version, publish new version
3. Add `**Note:** X.Y.Z is defective — use X.Y.Z+1` to CHANGELOG.md

---

## Common commands

```bash
sbt compile                        # All modules
sbt test                           # Scala + C++ tests (GPU tests skip if no GPU)
sbt "testOnly ClassName"           # Specific test
sbt nativeCompile                  # C++/CUDA native build
sbt package                        # Build jar (aborts if PTX absent — stub guard)
sbt publishLocal                   # Local ivy publish (aborts if PTX absent)
```

Pipeline monitoring:
```bash
gh run list --limit 5
gh run view <run-id>
```
