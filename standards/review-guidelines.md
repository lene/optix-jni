# Code Review Guidelines

Version: 1.0 (Sprint 28.4)

These guidelines are read by AI reviewers (Claude, DeepSeek). They define
what to flag and what to skip. The goal is signal, not noise.

---

## Project context

Scala 3 ray tracer. Three modules: `menger-app` (renderer, CLI),
`menger-common` (domain primitives), `optix-jni` (CUDA/C++ JNI bridge).
GPU ray tracing via NVIDIA OptiX. Rendering: Menger sponges (3D) and
tesseract sponges (4D).

---

## DO flag these

### Correctness
- Logic errors, off-by-one errors, incorrect numeric calculations
- Wrong alpha convention: `alpha = 0.0` means **fully transparent**;
  `alpha = 1.0` means **fully opaque**. Any reversal is a critical bug.
- Unsafe JNI assumptions: native pointer lifetimes, missing null checks at
  the JNI boundary, data type mismatches between Scala Long and C++ pointer
- GPU memory: CUDA allocations without corresponding frees, host/device
  memory confusion
- Missing edge-case handling for user-supplied inputs (negative dimensions,
  zero subdivisions, out-of-range colors, empty file paths)
- Incorrect mathematical operations in rendering math (ray-box intersection,
  AABB merging, color clamping, Beer-Lambert absorption)

### Architecture
- Cross-module dependency violations: `menger-app` may depend on
  `menger-common` and `optix-jni`; `menger-common` must not depend on either
  of the other two; `optix-jni` must not depend on `menger-app`
- Leaking implementation types across module boundaries (e.g. OptiX handles
  in `menger-common`, LibGDX types in `menger-common`)
- Breaking the JNI contract: changes to native method signatures without
  regenerating JNI headers; C++ side changes that don't match Scala `@native`
  declarations

### Agentic anti-patterns (the main reason for AI-specific review)
- Hardcoded paths, magic numbers without named constants
- Copy-pasted code where a small abstraction would serve all callsites (three
  or more identical or near-identical blocks is the threshold)
- Silent assumption of values that should be user-supplied (e.g. defaulting
  a version number, a path, or a parameter without documenting the assumption)
- Dead code introduced by the change (unused variables, unreachable branches,
  parameters that are never read)
- Over-engineering: abstractions, traits, or type parameters introduced for
  hypothetical future requirements not present in the current diff
- Test modifications that look like "make the test pass" rather than "fix the
  bug": changed expected values without explanation, deleted assertions,
  loosened tolerances without justification

### Performance
- Unnecessary recomputation inside hot loops (scene traversal, shader
  evaluation, pixel-buffer writes)
- Allocations inside rendering loops (GC pressure matters for frame rate)
- Blocking GPU operations where async alternatives exist

### Security (minimal surface, but note these)
- API keys, tokens, or credentials in code or tests
- Shell injection in scripts (unquoted variables in `sh -c` calls)

---

## DO NOT flag these (already enforced by tooling)

- Import ordering — enforced by Scalafix OrganizeImports
- Code formatting — enforced by scalafmt
- Missing docstrings — intentionally absent per project convention
- `var`, `while`, `asInstanceOf`, `throw` in production code — enforced by
  WartRemover; flag only if you see these bypass `@SuppressWarnings` without
  justification
- Unused imports — enforced by Scalafix
- Line length >100 — enforced by scalafmt
- Test framework choice — all tests use AnyFlatSpec by convention

---

## Severity guidance

- **error**: Would cause incorrect rendering, a crash, memory corruption, or
  a broken JNI boundary. Must be fixed before merge.
- **warning**: Degraded correctness, architectural drift, or a clear
  maintainability problem. Should be addressed.
- **info**: Style or minor improvement where the correct approach is not
  obvious from automated tools. Informational only.

---

## Output format

Output only the JSON object. No preamble, no explanation, no markdown fences.
