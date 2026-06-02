package io.github.lene.optix

import menger.common.{Material => CommonMaterial}

/** Backward-compatible alias for [[menger.common.Material]].
  *
  * New code may import `menger.common.Material` directly; this alias keeps the
  * `io.github.lene.optix.Material` API stable for existing consumers.
  */
type Material = CommonMaterial

/** Backward-compatible companion alias for [[menger.common.Material]]. */
val Material = CommonMaterial
