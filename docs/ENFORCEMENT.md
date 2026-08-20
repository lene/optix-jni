# Enforcement Audit: Policy → Mechanism Map

Living document. Reviewed at sprint close. Every ❌ row has an open issue;
resolve by implementing a gate or consciously accepting the gap. Follows the
format established in `menger/docs/ENFORCEMENT.md` (Sprint 36 A4).

**Status legend:**
- ✅ Enforced — structural gate; violations are rejected automatically
- ⚠️ Partial — enforced with known gaps
- 🤖 AI policy — behavioural instruction; mechanically unenforceable by design
- ❌ Unenforced — open gap, no gate yet

---

## Commit hygiene

| Policy | Source | Mechanism | Status |
|--------|--------|-----------|--------|
| No commits directly on `main` | CLAUDE.md §Critical rules | `standards/hooks/check-branch.sh` (pre-commit) | ✅ |
| No conflict markers or whitespace errors in staged diff | CLAUDE.md §Critical rules | `standards/hooks/check-staged-hygiene.sh` (pre-commit) | ✅ |
| No files > 5 MB staged | CLAUDE.md §Critical rules | `standards/hooks/check-staged-hygiene.sh` (pre-commit) | ✅ |
| Never `git add -A` | CLAUDE.md §Critical rules | 🤖 AI policy | 🤖 |
| Never push without explicit user confirmation | CLAUDE.md §Critical rules | 🤖 AI policy | 🤖 |

---

## Test discipline

| Policy | Source | Mechanism | Status |
|--------|--------|-----------|--------|
| Tests must pass before commit | CLAUDE.md §Critical rules | pre-commit: `sbt test` (GPU tests skip via `assume()` if no GPU) | ✅ |
| Tests must pass before push | CLAUDE.md §Critical rules | pre-push: `sbt test` | ✅ |
| Modified/deleted test files require `Test-Change:` trailer | CLAUDE.md §Critical rules (shared) | `standards/hooks/check-test-justification.sh` (pre-push) | ✅ |
| ArchUnit architecture rules | — | CI `archunit` job: `sbt "testOnly *ArchUnit*"` (self-hosted GPU runner) | ✅ |
| Coverage floor / ratchet | — | Not implemented — no `.coverage_baseline` or CI coverage gate exists in this repo | ❌ |
| Escaped-defect fix carries a regression test + `docs/QA_INCIDENTS.md` entry | `../docs/QA_STRATEGY.md` §O5 (workspace); `../docs/QA_INCIDENTS.md` | 🤖 AI policy | 🤖 |

Mechanized `QA-Incident:` trailer check deferred per Sprint 36 SPRINT36.md E3 ("later if
leaky") — add only if the 🤖 row proves insufficient in practice.

---

## Code quality — Scala

| Policy | Source | Mechanism | Status |
|--------|--------|-----------|--------|
| Scalafix passes (`OrganizeImports`, `DisableSyntax`) | CLAUDE.md §Code style (shared) | pre-commit + pre-push: `sbt "scalafix --check"`; CI `scalafix` job | ✅ |

---

## Code quality — C++/CUDA

| Policy | Source | Mechanism | Status |
|--------|--------|-----------|--------|
| cppcheck (warnings, style) | CLAUDE.md §Code style (shared) | pre-push (native changes, if installed) + CI `cppcheck` job (unconditional) | ✅ |
| clang-tidy | CLAUDE.md §Code style (shared) | CI `clang-tidy` job (self-hosted GPU runner) — not run in the local pre-push hook | ⚠️ CI-only |
| No raw `std::cerr`/`std::cout`/`printf` in production native (F12) | — | `scripts/check-native-logging.sh`, run in pre-push (native changes) and CI `native-logging` job | ✅ |
| JNI API doc completeness | — | CI `doc-completeness` job: `scripts/check-doc-completeness.sh` | ✅ |
| Alpha: 0.0 = transparent, 1.0 = opaque (never inverted) | CLAUDE.md §Shared conventions | Unenforced — no static check | ❌ |

---

## Build integrity

| Policy | Source | Mechanism | Status |
|--------|--------|-----------|--------|
| Stub build (`nvcc` absent) must never be published to Maven Central | CLAUDE.md §Build modes | `Compile/packageBin` guard in `build.sbt` aborts if PTX absent | ✅ |
| CUDA/OptiX environment present before native build | CLAUDE.md §Runtime | pre-commit + pre-push: environment check (warn-only; publish guard is the real backstop) | ⚠️ warn-only |

---

## Version & release

| Policy | Source | Mechanism | Status |
|--------|--------|-----------|--------|
| Changelog entry exists, dated, linked for the declared version | CLAUDE.md §Release workflow | pre-push Changelog & Version check; CI `changelog-updated` job (PRs) | ✅ |
| Git tag not already used by a different version | — | pre-push version check; CI `version-not-tagged` job | ✅ |
| Release triggers on merge to `main` | — | CI `create-tag` job (push to main) → tag → `publish` job (tag ref or `workflow_dispatch`, since a CI-pushed tag cannot itself trigger a tag-ref run) | ✅ |
| Published artifact installs and runs (Java + Kotlin smoke tests) | — | CI `smoke-tests` (pre-publish, local build) + `post-publish-smoke` (post-publish, from Maven Central) | ✅ |
| Maven Central incident protocol documented (artifacts are permanent) | CLAUDE.md §Maven Central incident protocol | Documentation only | 🤖 |

---

## Cross-repo standards

| Policy | Source | Mechanism | Status |
|--------|--------|-----------|--------|
| Shared configs byte-identical across menger / menger-common / optix-jni | workspace `shared/standards/` | Scheduled workspace CI: `scripts/check-standards-drift.sh` (daily, `check-drift.yml`) | ✅ |
| Local standards parity before every commit | workspace `shared/standards/` | pre-commit: `scripts/check-standards-drift.sh --local` | ✅ |
| Standards sync to sibling repos reviewed like any code change | workspace `shared/standards/README.md` | Manual: `scripts/sync-standards.sh` (no silent cross-repo writes) | ⚠️ |

---

## Agentic workflow (AI-only policies)

These policies govern AI agent behaviour and are structurally unenforceable by
a gate. Listed for completeness and to confirm the gap is consciously
accepted.

| Policy | Source | Status |
|--------|--------|--------|
| Never infer values the user should provide (version numbers, branch names, paths) | CLAUDE.md §Critical rules | 🤖 |
| Never delete data without explicit user confirmation | CLAUDE.md §Critical rules | 🤖 |
| Never rewrite a test to make it pass without investigation | CLAUDE.md §Critical rules | 🤖 |
| When a skill says "confirm with user", it is a hard stop | CLAUDE.md §Critical rules | 🤖 |
| Always monitor CI pipeline after push | CLAUDE.md §Critical rules | 🤖 |

---

## Open issues

| Policy gap | Action |
|-----------|--------|
| No coverage floor/ratchet | Adopt menger's `.coverage_baseline` pattern if/when coverage regressions become a problem here |
| Alpha-channel convention has no static check | Cross-repo gap, same as menger docs/ENFORCEMENT.md #4 |
| CUDA/OptiX environment check is warn-only in the hook | Acceptable today: the real backstop is the `build.sbt` publish guard, which is a hard failure |
