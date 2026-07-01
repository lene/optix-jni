package io.github.lene.optix

import menger.common.Color
import menger.common.Vector
import menger.common.x
import menger.common.y
import menger.common.z

/** Plane helpers exposed through [[OptiXRenderer]].
  *
  * Axis ids use native convention: `0 = x`, `1 = y`, `2 = z`. Plane distances
  * and positions are world units. Instance-returning methods return a native
  * instance id, or `-1` on native failure.
  */
private[optix] trait OptiXPlaneApi:
  this: OptiXRenderer =>

  /** Removes all legacy clipping or ground-plane definitions. */
  def clearPlanes(): Unit = clearPlanesNative()

  /** Adds an axis-aligned legacy plane. */
  def addPlane(axis: Int, positive: Boolean, value: Float): Unit = addPlaneNative(axis, positive, value)

  /** Adds an axis-aligned legacy plane with one RGB color. */
  def addPlaneSolidColor(axis: Int, positive: Boolean, value: Float, r: Float, g: Float, b: Float): Unit =
    addPlaneSolidColorNative(axis, positive, value, r, g, b)

  /** Adds an axis-aligned legacy plane with a two-color checker pattern. */
  def addPlaneCheckerColors(axis: Int, positive: Boolean, value: Float,
                            r1: Float, g1: Float, b1: Float,
                            r2: Float, g2: Float, b2: Float): Unit =
    addPlaneCheckerColorsNative(axis, positive, value, r1, g1, b1, r2, g2, b2)

  /** Adds an axis-aligned legacy plane with material properties and optional texture. */
  def addPlaneSolidColorWithMaterial(
    axis: Int, positive: Boolean, value: Float,
    color: Color, material: Material, textureIndex: Int = -1): Unit =
    addPlaneSolidColorWithMaterialNative(
      axis, positive, value,
      color.r, color.g, color.b,
      material.roughness, material.metallic, material.specular, material.emission,
      textureIndex)

  /** Adds an axis-aligned checker plane with material properties and optional texture. */
  def addPlaneCheckerColorsWithMaterial(
    axis: Int, positive: Boolean, value: Float,
    color1: Color, color2: Color, material: Material, textureIndex: Int = -1): Unit =
    addPlaneCheckerColorsWithMaterialNative(
      axis, positive, value,
      color1.r, color1.g, color1.b,
      color2.r, color2.g, color2.b,
      material.roughness, material.metallic, material.specular, material.emission,
      textureIndex)

  /** Adds a checker plane from reusable plane and checker descriptors. */
  def addPlaneCheckerColorsWithMaterial(
    plane: PlaneSpec, checker: CheckerPattern, material: Material): Unit =
    addPlaneCheckerColorsWithMaterial(
      plane.axis, plane.positive, plane.value,
      checker.color1, checker.color2, material)

  /** Adds a checker plane from descriptors and a base-color texture index. */
  def addPlaneCheckerColorsWithMaterial(
    plane: PlaneSpec, checker: CheckerPattern, material: Material, textureIndex: Int): Unit =
    addPlaneCheckerColorsWithMaterial(
      plane.axis, plane.positive, plane.value,
      checker.color1, checker.color2, material, textureIndex)

  /** Adds a general plane IAS instance.
    *
    * @param normal world-space plane normal
    * @param distance signed distance from origin in world units
    * @param checkerColor second checker color, or `null` for a solid-color plane
    * @param checkerSize checker tile size in world units
    * @return instance id `>= 0`, or `-1` on native failure
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
    val (cauchy_a, cauchy_b) = Material.cauchyCoefficients(material.ior, material.dispersion)
    addPlaneInstanceNative(
      normal.x, normal.y, normal.z,
      distance,
      material.color.r, material.color.g, material.color.b, material.color.a,
      material.ior, material.roughness, material.metallic, material.specular, material.emission,
      material.filmThickness,
      cauchy_a, cauchy_b,
      r2, g2, b2, solidColor, checkerSize
    )

  /** Adds a solid-color plane IAS instance from color and IOR.
    *
    * @return instance id `>= 0`, or `-1` on native failure
    */
  def addPlaneInstance(
    normal: Vector[3],
    distance: Float,
    color: Color,
    ior: Float
  ): Int =
    addPlaneInstance(normal, distance, Material(color, ior), null, 1.0f) // scalafix:ok DisableSyntax.null
