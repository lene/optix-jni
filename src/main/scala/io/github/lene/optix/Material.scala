package io.github.lene.optix

import menger.common.{Material => CommonMaterial}

/** Backward-compatible alias for [[menger.common.Material]].
  *
  * New code may import `menger.common.Material` directly; this alias keeps the
  * `io.github.lene.optix.Material` API stable for existing consumers.
  *
  * On an opaque dielectric (alpha 1, `metallic` 0), `specular` and `roughness` shape the
  * highlight each direct light (directional, point, area) leaves on top of the diffuse shading
  * (optix-jni#105): a GGX microfacet lobe with Schlick Fresnel, its normal-incidence reflectance
  * `0.08 * specular` (Disney; 0.04 at the default 0.5) and its width `roughness` (GGX alpha =
  * `roughness`²). The highlight is untinted, follows the light's shadow, and shows only where
  * the viewer and the light are on the same side of the surface. `specular` 0 gives none, so
  * `Material.matte` shades as before; ambient and environment (IBL) light give none. Metals and
  * refractive or transparent materials keep their own reflection model, except that a triangle
  * mesh face faded by a non-refractive alpha below 1 (Parchment) is shaded as opaque, highlight
  * included, before it is blended with what lies behind it.
  */
type Material = CommonMaterial

/** Backward-compatible companion alias for [[menger.common.Material]]. */
val Material = CommonMaterial
