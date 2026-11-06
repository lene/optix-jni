package io.github.lene.optix

/** How the ambient fill, the light every surface gets however the lights are placed (0.3 times
  * its colour), is computed.
  *
  * Open surfaces get the same fill from every model; the models differ in what they do to
  * surfaces that other geometry covers (optix-jni#106).
  */
enum AmbientModel:

  /** The fill is the same everywhere: a cavity is as bright as an open face. */
  case Constant

  /** The fill is weighted by ambient occlusion: the share of the hemisphere above the surface
    * that is open sky, so cavities and contact areas darken while a surface with nothing above
    * it keeps exactly the constant fill.
    *
    * Visibility is estimated with `samples` rays, cosine-weighted and stratified (4x4 strata for
    * 16 samples) with a rotation per pixel; accumulating frames or more samples reduce the
    * noise. A transparent occluder darkens by its shadow attenuation, not fully.
    *
    * @param samples occlusion rays per surface point, 1 to 64
    * @param radius how far from the surface an occluder still darkens (world units, at least
    *               0.01); the default counts every occluder, at any distance
    */
  case Occlusion(samples: Int = 16, radius: Float = Float.PositiveInfinity)
