# Architecture Review Guidelines

Version: 1.0 (Sprint 28.4)

Applied in addition to `review-guidelines.md` when the diff touches
architecture-relevant paths (listed in `standards/architecture-paths.txt`).
Read by AI reviewers as a supplementary context layer.

---

## arc42 alignment

The project uses arc42 as its architecture documentation standard
(`docs/arc42/`). When reviewing architectural changes, check:

- **§9 Architectural Decisions**: Is a new significant decision documented?
  Significant means: a new dependency, a changed module boundary, a new
  external system integration, a changed data contract at the JNI boundary,
  or a rendering-pipeline topology change.
- **§10 Quality Requirements**: Does the change affect a quality scenario
  (latency, memory footprint, maintainability, portability)? Flag if a
  scenario is degraded without documentation.
- **§11 Risks and Technical Debt**: Does the change introduce a new known
  risk (e.g. GPU driver version sensitivity, JNI memory leak potential,
  platform-specific behaviour)? Flag if the risk is not already documented.

You do not need to check §1–§8 unless the diff explicitly modifies those
sections.

---

## Module boundary rules

| Module | May depend on | Must not depend on |
|--------|---------------|--------------------|
| `menger-common` | standard library only | `menger-app`, `optix-jni`, LibGDX |
| `optix-jni` | `menger-common`, JNI stdlib | `menger-app`, LibGDX |
| `menger-app` | `menger-common`, `optix-jni`, LibGDX | (no restriction beyond circular) |

Flag any build.sbt change that adds a dependency violating this table.

---

## JNI boundary contract

The JNI boundary is between `optix-jni/src/main/scala/` (Scala `@native`
declarations) and `optix-jni/src/main/native/` (C++ implementations).

Flag:
- Native method signatures changed in Scala without corresponding C++ change
  (or vice versa)
- New native methods that take or return `AnyRef` / `Object` / raw `Long`
  without clear documentation of the lifetime contract
- C++ side returning error codes that the Scala side ignores
- New CUDA kernel launches without error checking after the launch

---

## Build definition changes

Flag in build.sbt / project/*.scala / CMakeLists.txt:
- New external dependencies without a justification comment
- Version pins removed (floating versions introduce non-reproducibility)
- Compiler flags weakened (e.g. `-Wall` removed, `-Werror` suppressed)
- New cross-version matrix entries without corresponding CI coverage

---

## CI pipeline changes

Flag in .gitlab-ci.yml / .github/workflows/**:
- New jobs that always run without a clear need (CI budget)
- Secrets referenced in `script:` without masking (`$VARIABLE` in echo /
  debug output)
- `allow_failure: true` added to a job that was previously mandatory
- `retry:` increased to hide intermittent failures (cover up, not fix)

---

## What NOT to flag

- Minor README or doc-only changes — no architectural impact
- Test additions for new code — expected, not a risk
- Changes inside a single module that don't touch its public API

---

## Severity guidance (architecture-specific)

- **error**: Module boundary violated, JNI contract broken, build
  reproducibility lost. Breaks the system invariants that make the
  codebase maintainable.
- **warning**: Undocumented architectural decision, unreviewed new
  dependency, quality scenario silently degraded.
- **info**: Missing arc42 update that would be nice to have.
