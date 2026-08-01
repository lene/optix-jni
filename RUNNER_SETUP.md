# Runner setup — moved

GitLab GPU-runner setup moved to the **workspace repo** (`menger-toplevel`), at
`infra/RUNNER_SETUP.md`, alongside the shared runner hardening (`infra/ci-runners/`). It
documents menger's GitLab runner — this repo uses GitHub Actions, so the doc never belonged
here — and the workspace is where one host's shared runner envelope is described.

From a full workspace checkout: `../infra/RUNNER_SETUP.md`.

This repo's own GitHub Actions runner is registered per the procedure in
`../infra/ci-runners/README.md`.
