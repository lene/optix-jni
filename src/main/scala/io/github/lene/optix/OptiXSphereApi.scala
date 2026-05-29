package io.github.lene.optix

import menger.common.Color
import menger.common.Const
import menger.common.Vector
import menger.common.x
import menger.common.y
import menger.common.z

private[optix] trait OptiXSphereApi:
  this: OptiXRenderer =>

  def setSphere(center: Vector[3], radius: Float): Unit =
    setSphere(center.x, center.y, center.z, radius)

  def setSphereColor(color: Color): Unit =
    setSphereColorNative(color.r, color.g, color.b, color.a)

  /** @return instance ID (>= 0), or -1 on failure */
  def addSphereInstance(transform: Array[Float], material: Material): Int =
    require(transform.length == Const.Renderer.transformMatrixSize, s"Transform must have ${Const.Renderer.transformMatrixSize} elements (4x3 matrix), got ${transform.length}")
    addSphereInstanceNative(
      transform,
      material.color.r, material.color.g, material.color.b, material.color.a,
      material.ior, material.roughness, material.metallic, material.specular, material.emission,
      material.filmThickness
    )

  /** @return instance ID (>= 0), or -1 on failure */
  def addSphereInstance(transform: Array[Float], color: Color, ior: Float): Int =
    addSphereInstance(transform, Material(color, ior))

  /** @return instance ID (>= 0), or -1 on failure */
  def addSphereInstance(position: Vector[3], material: Material): Int =
    val transform = Array(
      1.0f, 0.0f, 0.0f, position.x,
      0.0f, 1.0f, 0.0f, position.y,
      0.0f, 0.0f, 1.0f, position.z
    )
    addSphereInstance(transform, material)

  /** @return instance ID (>= 0), or -1 on failure */
  def addSphereInstance(position: Vector[3], color: Color, ior: Float): Int =
    addSphereInstance(position, Material(color, ior))
