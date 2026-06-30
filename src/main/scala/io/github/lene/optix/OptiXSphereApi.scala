package io.github.lene.optix

import menger.common.Color
import menger.common.Const
import menger.common.Vector
import menger.common.x
import menger.common.y
import menger.common.z

/** Sphere helpers exposed through [[OptiXRenderer]].
  *
  * The legacy `setSphere` path configures one sphere in non-IAS mode.
  * `addSphereInstance` adds an IAS instance and returns a native instance id,
  * or `-1` when native allocation or validation fails.
  */
private[optix] trait OptiXSphereApi:
  this: OptiXRenderer =>

  /** Sets the legacy single-sphere geometry in world units. */
  def setSphere(center: Vector[3], radius: Float): Unit =
    setSphere(center.x, center.y, center.z, radius)

  /** Sets the legacy single-sphere RGBA color.
    *
    * @deprecated Use [[addSphereInstance]] with [[Material]] instead.
    */
  @deprecated("Use addSphereInstance with Material", "0.1.5")
  def setSphereColor(color: Color): Unit =
    setSphereColorNative(color.r, color.g, color.b, color.a)

  /** Adds a sphere IAS instance with an explicit 4x3 row-major transform.
    *
    * @return instance id `>= 0`, or `-1` on native failure
    */
  def addSphereInstance(transform: Array[Float], material: Material): Int =
    require(transform.length == Const.Renderer.transformMatrixSize, s"Transform must have ${Const.Renderer.transformMatrixSize} elements (4x3 matrix), got ${transform.length}")
    val (cauchy_a, cauchy_b) = Material.cauchyCoefficients(material.ior, material.dispersion)
    addSphereInstanceNative(
      transform,
      material.color.r, material.color.g, material.color.b, material.color.a,
      material.ior, material.roughness, material.metallic, material.specular, material.emission,
      material.filmThickness,
      cauchy_a, cauchy_b
    )

  /** Adds a sphere IAS instance from color and index of refraction.
    *
    * @return instance id `>= 0`, or `-1` on native failure
    */
  def addSphereInstance(transform: Array[Float], color: Color, ior: Float): Int =
    addSphereInstance(transform, Material(color, ior))

  /** Adds a unit-scale translated sphere IAS instance.
    *
    * @return instance id `>= 0`, or `-1` on native failure
    */
  def addSphereInstance(position: Vector[3], material: Material): Int =
    val transform = Array(
      1.0f, 0.0f, 0.0f, position.x,
      0.0f, 1.0f, 0.0f, position.y,
      0.0f, 0.0f, 1.0f, position.z
    )
    addSphereInstance(transform, material)

  /** Adds a unit-scale translated sphere IAS instance from color and IOR.
    *
    * @return instance id `>= 0`, or `-1` on native failure
    */
  def addSphereInstance(position: Vector[3], color: Color, ior: Float): Int =
    addSphereInstance(position, Material(color, ior))
