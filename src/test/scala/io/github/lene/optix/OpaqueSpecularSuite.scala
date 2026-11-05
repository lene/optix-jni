package io.github.lene.optix

import io.github.lene.optix.ImageValidation.getRGBAt
import io.github.lene.optix.ThresholdConstants.TEST_IMAGE_SIZE
import menger.common.Color
import menger.common.TriangleMeshData
import menger.common.Vector
import menger.common.x
import menger.common.y
import menger.common.z
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

// The direct-light highlight of opaque dielectrics (optix-jni#105): GGX with Schlick Fresnel,
// F0 = 0.08 * specular, driven by a material's `specular` and `roughness`. Each scene is rendered
// twice, in a material and in Matte of the same colour; the highlight is what the first adds.
// Colours are dark enough that the diffuse share stays well below saturation.
class OpaqueSpecularSuite extends AnyFlatSpec with Matchers with RendererFixture:

  OptiXRenderer.isLibraryLoaded shouldBe true

  private val Size = TEST_IMAGE_SIZE
  private val Grey = Color(0.3f, 0.3f, 0.3f, 1.0f)
  private val DarkGrey = Color(0.2f, 0.2f, 0.2f, 1.0f)
  private val Matte = Material.matte(Grey)
  private val Plastic = Material.plastic(Grey)  // specular 0.5, roughness 0.3
  private val Origin = Vector[3](0.0f, 0.0f, 0.0f)
  private val YUp = Vector[3](0.0f, 1.0f, 0.0f)
  private val YDown = Vector[3](0.0f, -1.0f, 0.0f)
  private val CameraFov = 60.0f
  private val CentreRegionRadius = 3
  // A highlight's brightness over Matte, in 0..255 levels, that is unmistakably there.
  private val MinHighlightGain = 30.0
  // How far (pixels) a highlight's centroid may sit from where the mirror direction puts it.
  private val CentroidTolerancePixels = 4.0

  /** Per pixel, how much brighter `image` is than `base` (0 where it is not). */
  private def excess(image: Array[Byte], base: Array[Byte]): IndexedSeq[Int] =
    for
      y <- 0 until Size.height
      x <- 0 until Size.width
    yield math.max(
      0, getRGBAt(image, Size, x, y).brightness - getRGBAt(base, Size, x, y).brightness
    )

  private case class Highlight(peak: Int, area: Int, centreX: Double, centreY: Double)

  /** The pixels at least half as bright over the base as the brightest one. */
  private def highlight(gain: IndexedSeq[Int]): Highlight =
    val peak = gain.max
    val core = gain.indices.filter(i => gain(i) * 2 >= peak)
    Highlight(
      peak, core.size,
      core.map(_ % Size.width).sum.toDouble / core.size,
      core.map(_ / Size.width).sum.toDouble / core.size
    )

  private def centreBrightness(image: Array[Byte]): Double =
    TestUtilities.regionBrightness(
      image, Size,
      TestUtilities.Region.centered(Size.width / 2, Size.height / 2, CentreRegionRadius)
    )

  private def render(build: => Unit): Array[Byte] =
    renderer.clearAllInstances()
    build
    renderImage(Size)

  // ========== A sphere lit from the camera: the highlight sits at the image centre ==========

  private val SphereRadius = 0.6f
  private val FrontCamera = Vector[3](0.0f, 0.0f, 3.0f)
  // Travels from the camera's side toward the scene: at the sphere's front point the mirror
  // direction of the view is the light, so the highlight is centred in the image.
  private val FromCamera = Vector[3](0.0f, 0.0f, -1.0f)
  // The rough-vs-smooth comparison: the strongest specular, a stronger light.
  private val BrightLight = 2.0f
  private val SmoothRoughness = 0.2f
  private val RoughRoughness = 0.8f

  private def renderFrontLitSphere(material: Material, intensity: Float = 1.0f): Array[Byte] =
    render:
      renderer.setCamera(FrontCamera, Origin, YUp, CameraFov)
      renderer.setLight(FromCamera, intensity)
      val _ = renderer.addSphereInstance(sphereTransform(0.0f, 0.0f, 0.0f, SphereRadius), material)

  "An opaque dielectric" should "render Plastic differently from Matte" in:
    renderFrontLitSphere(Plastic) should not equal renderFrontLitSphere(Matte)

  it should "show Plastic's highlight centred on the light's mirror direction" in:
    val spot = highlight(excess(renderFrontLitSphere(Plastic), renderFrontLitSphere(Matte)))
    spot.peak.toDouble should be > MinHighlightGain
    spot.centreX shouldBe (Size.width / 2.0) +- CentroidTolerancePixels
    spot.centreY shouldBe (Size.height / 2.0) +- CentroidTolerancePixels

  it should "render a specular-0 material exactly like Matte at any roughness" in:
    renderFrontLitSphere(Matte.copy(roughness = SmoothRoughness)) shouldEqual
      renderFrontLitSphere(Matte)

  it should "spread a rougher highlight wider and dimmer" in:
    def spotAt(roughness: Float): Highlight =
      val glossy = Material.plastic(DarkGrey).copy(roughness = roughness, specular = 1.0f)
      highlight(excess(
        renderFrontLitSphere(glossy, BrightLight),
        renderFrontLitSphere(Material.matte(DarkGrey), BrightLight)
      ))
    val smooth = spotAt(SmoothRoughness)
    val rough = spotAt(RoughRoughness)
    rough.peak should be > 0
    rough.peak should be < smooth.peak
    rough.area should be > smooth.area

  // ========== A flat plane seen at an angle, light in the mirror direction of the view ==========

  private val PlaneCamera = Vector[3](0.0f, 2.0f, 3.0f)
  // The mirror image of the view direction at the origin, so the highlight is at the centre.
  private val MirrorLight = Vector[3](0.0f, -2.0f, 3.0f)       // direction the light travels
  private val LightFromBelow = Vector[3](0.0f, 2.0f, 3.0f)     // the plane's other side
  private val OccluderDistance = 1.0f
  private val OccluderRadius = 0.25f

  private def addOccluderInLight(): Unit =
    val toLight = -MirrorLight * (OccluderDistance / MirrorLight.len)
    val _ = renderer.addSphereInstance(
      sphereTransform(toLight.x, toLight.y, toLight.z, OccluderRadius), Matte
    )

  private def renderPlane(
      material: Material, normal: Vector[3] = YUp, light: Vector[3] = MirrorLight,
      shadows: Boolean = false, occluded: Boolean = false
  ): Array[Byte] =
    render:
      renderer.setShadows(shadows)
      renderer.setCamera(PlaneCamera, Origin, YUp, CameraFov)
      renderer.setLight(light, 1.0f)
      val _ = renderer.addPlaneInstance(normal, 0.0f, material)
      if occluded then addOccluderInLight()

  "A flat face" should "show a highlight where it mirrors the light" in:
    centreBrightness(renderPlane(Plastic)) should be >
      centreBrightness(renderPlane(Matte)) + MinHighlightGain

  it should "show no highlight where the light is shadowed" in:
    val shadowed = renderPlane(Plastic, shadows = true, occluded = true)
    centreBrightness(shadowed) shouldBe
      centreBrightness(renderPlane(Matte, shadows = true, occluded = true))
    centreBrightness(renderPlane(Plastic, shadows = true)) should be >
      centreBrightness(shadowed) + MinHighlightGain

  it should "show no highlight from a light behind it, whichever way its normal points" in:
    for normal <- Seq(YUp, YDown) do
      renderPlane(Plastic, normal, LightFromBelow) shouldEqual
        renderPlane(Matte, normal, LightFromBelow)

  it should "show the highlight on the viewer's side when its normal points away" in:
    centreBrightness(renderPlane(Plastic, YDown)) should be >
      centreBrightness(renderPlane(Matte, YDown)) + MinHighlightGain

  // ========== Every primitive shades its opaque dielectrics through the same highlight ==========

  private val QuadHalfSize = 0.8f
  private val Identity = Array(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f)
  private val ConeApex = Vector[3](0.0f, 0.7f, 0.0f)
  private val ConeBase = Vector[3](0.0f, -0.6f, 0.0f)
  private val ConeRadius = 0.6f
  private val ConeHeight = ConeApex.y - ConeBase.y
  // The cone's front flank tilts up, normal (0, r, h) / s: no flank faces the camera, so a light
  // from the camera has no mirror point on it. This light, the view direction (0, 0, 1)
  // reflected off that normal, (0, 2rh, h^2 - r^2) / s^2, has one; it travels the other way.
  private val ConeFlankLight = Vector[3](
    0.0f, -2.0f * ConeRadius * ConeHeight, ConeRadius * ConeRadius - ConeHeight * ConeHeight
  )
  private val CurvePoints =
    Array(-0.9f, -0.3f, 0f, -0.3f, 0.3f, 0f, 0.3f, -0.3f, 0f, 0.9f, 0.3f, 0f)
  private val CurveWidth = 0.2f

  // A quad facing the camera: two triangles in the z = 0 plane, normals +z.
  private def quad: TriangleMeshData =
    val h = QuadHalfSize
    val corners = Seq((-h, -h), (h, -h), (h, h), (-h, h))
    TriangleMeshData(
      corners.flatMap((cx, cy) => Seq(cx, cy, 0f, 0f, 0f, 1f)).toArray,
      Array(0, 1, 2, 0, 2, 3),
      TriangleMeshData.LegacyVertexStride
    )

  private def frontLit(add: Material => InstanceId, light: Vector[3] = FromCamera)(
      material: Material
  ): Array[Byte] =
    render:
      renderer.setCamera(FrontCamera, Origin, YUp, CameraFov)
      renderer.setLight(light, 1.0f)
      val _ = add(material)

  private val primitives: Seq[(String, Material => Array[Byte])] = Seq(
    "triangle mesh" -> frontLit { m =>
      val _ = renderer.addTriangleMesh(quad)
      renderer.addTriangleMeshInstance(Identity, m)
    },
    "curve" -> frontLit(
      renderer.addCurveInstance(CurvePoints, Array.fill(CurvePoints.length / 3)(CurveWidth), _)
    ),
    "legacy plane" -> { m =>
      render:
        renderer.setCamera(PlaneCamera, Origin, YUp, CameraFov)
        renderer.setLight(MirrorLight, 1.0f)
        renderer.clearPlanes()
        renderer.addPlaneSolidColorWithMaterial(1, positive = true, 0.0f, Grey, m)
    }
  )

  for (name, renderWith) <- primitives do
    s"A $name" should "show Plastic's highlight and render a specular-0 material like Matte" in:
      val matte = renderWith(Matte)
      highlight(excess(renderWith(Plastic), matte)).peak.toDouble should be > MinHighlightGain
      renderWith(Matte.copy(roughness = SmoothRoughness)) shouldEqual matte

  private def renderCone(material: Material, light: Vector[3]): Array[Byte] =
    frontLit(renderer.addConeInstance(ConeApex, ConeBase, ConeRadius, _), light)(material)

  "A cone" should "render Plastic differently from Matte, and a specular-0 material like Matte" in:
    val matte = renderCone(Matte, FromCamera)
    renderCone(Plastic, FromCamera) should not equal matte
    renderCone(Matte.copy(roughness = SmoothRoughness), FromCamera) shouldEqual matte

  // __intersection__cone reports a flank normal tilted toward the base instead of the apex
  // (hv/|hv| * cos - axis * sin uses the apex-to-hit direction where the radial one belongs), so
  // the highlight shows under a light from below, not from this one.
  it should "show the highlight where its flank mirrors the light" in:
    pendingUntilFixed:
      val gain = excess(renderCone(Plastic, ConeFlankLight), renderCone(Matte, ConeFlankLight))
      highlight(gain).peak.toDouble should be > MinHighlightGain

  // ========== A plinth (cylinder) seen from above, as in CylinderSuite ==========

  private val PlinthRadius = 1.5f
  private val PlinthBottom = -0.2f
  private val TopDownCamera = Vector[3](0.0f, 4.0f, 0.0f)
  private val TopDownUp = Vector[3](0.0f, 0.0f, -1.0f)
  private val HighlightX = 0.6f
  // Reflects the view from the cap at (HighlightX, 0, 0) into the light.
  private val CapMirrorLight = Vector[3](-HighlightX, -TopDownCamera.y, 0.0f)

  private def renderPlinthTopDown(material: Material): Array[Byte] =
    render:
      renderer.setCamera(TopDownCamera, Origin, TopDownUp, CameraFov)
      renderer.setLight(CapMirrorLight, 1.0f)
      val _ = renderer.addCylinderInstance(
        Vector[3](0.0f, PlinthBottom, 0.0f), Origin, PlinthRadius, material
      )

  "A plinth's top cap" should "show the highlight where it mirrors the light" in:
    val spot = highlight(excess(renderPlinthTopDown(Plastic), renderPlinthTopDown(Matte)))
    // The image is mirrored horizontally, so world +x is left of the centre (CylinderSuite).
    val halfWidthWorld = TopDownCamera.y * math.tan(math.toRadians(CameraFov / 2.0))
    val expectedColumn = Size.width / 2.0 * (1.0 - HighlightX / halfWidthWorld)
    spot.peak.toDouble should be > MinHighlightGain
    spot.centreX shouldBe expectedColumn +- CentroidTolerancePixels
    spot.centreY shouldBe (Size.height / 2.0) +- CentroidTolerancePixels
