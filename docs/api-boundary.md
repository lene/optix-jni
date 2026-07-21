# API boundary — moved

The optix-jni ↔ menger API boundary contract now lives in the **workspace repo**
(`menger-toplevel`), at `docs/architecture/`, alongside the other two halves of the same
contract (the 1.0 API scope audit and the decoupling decision record) which previously sat
in the menger repo. Splitting the contract across two repos meant neither side referenced
the other.

From a full workspace checkout: `../../docs/architecture/api-boundary.md`.

optix-jni's own **1.0 SemVer stability review** stays here, in
[docs/api-review-1.0.md](api-review-1.0.md) — it is coupled to this repo's release process,
not to the menger boundary.
