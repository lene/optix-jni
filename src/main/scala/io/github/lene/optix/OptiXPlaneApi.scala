package io.github.lene.optix

import menger.common.Color
import menger.common.Vector
import menger.common.x
import menger.common.y
import menger.common.z

private[optix] trait OptiXPlaneApi:
  this: OptiXRenderer =>

  def clearPlanes(): Unit = clearPlanesNative()

  def addPlane(axis: Int, positive: Boolean, value: Float): Unit = addPlaneNative(axis, positive, value)

  def addPlaneSolidColor(axis: Int, positive: Boolean, value: Float, r: Float, g: Float, b: Float): Unit =
    addPlaneSolidColorNative(axis, positive, value, r, g, b)

  def addPlaneCheckerColors(axis: Int, positive: Boolean, value: Float,
                            r1: Float, g1: Float, b1: Float,
                            r2: Float, g2: Float, b2: Float): Unit =
    addPlaneCheckerColorsNative(axis, positive, value, r1, g1, b1, r2, g2, b2)

  def addPlaneSolidColorWithMaterial(
    axis: Int, positive: Boolean, value: Float,
    color: Color, material: Material, textureIndex: Int = -1): Unit =
    addPlaneSolidColorWithMaterialNative(
      axis, positive, value,
      color.r, color.g, color.b,
      material.roughness, material.metallic, material.specular, material.emission,
      textureIndex)

  def addPlaneCheckerColorsWithMaterial(
    axis: Int, positive: Boolean, value: Float,
    color1: Color, color2: Color, material: Material, textureIndex: Int = -1): Unit =
    addPlaneCheckerColorsWithMaterialNative(
      axis, positive, value,
      color1.r, color1.g, color1.b,
      color2.r, color2.g, color2.b,
      material.roughness, material.metallic, material.specular, material.emission,
      textureIndex)

  def addPlaneCheckerColorsWithMaterial(
    plane: PlaneSpec, checker: CheckerPattern, material: Material): Unit =
    addPlaneCheckerColorsWithMaterial(
      plane.axis, plane.positive, plane.value,
      checker.color1, checker.color2, material)

  def addPlaneCheckerColorsWithMaterial(
    plane: PlaneSpec, checker: CheckerPattern, material: Material, textureIndex: Int): Unit =
    addPlaneCheckerColorsWithMaterial(
      plane.axis, plane.positive, plane.value,
      checker.color1, checker.color2, material, textureIndex)

  /** @param checkerColor second checker color, or null for solid-color plane
    * @return instance ID (>= 0), or -1 on failure
    */
  def addPlaneInstance(
    normal: Vector[3],
    distance: Float,
    material: Material,
    checkerColor: Color = null, // scalafix:ok DisableSyntax.null
    checkerSize: Float = 1.0f
  ): Int =
    val (r2, g2, b2, solidColor) =
      if checkerColor != null then (checkerColor.r, checkerColor.g, checkerColor.b, 0) // scalafix:ok DisableSyntax.null
      else (0f, 0f, 0f, 1)
    addPlaneInstanceNative(
      normal.x, normal.y, normal.z,
      distance,
      material.color.r, material.color.g, material.color.b, material.color.a,
      material.ior, material.roughness, material.metallic, material.specular, material.emission,
      material.filmThickness,
      r2, g2, b2, solidColor, checkerSize
    )

  /** @return instance ID (>= 0), or -1 on failure */
  def addPlaneInstance(
    normal: Vector[3],
    distance: Float,
    color: Color,
    ior: Float
  ): Int =
    addPlaneInstance(normal, distance, Material(color, ior), null, 1.0f) // scalafix:ok DisableSyntax.null
