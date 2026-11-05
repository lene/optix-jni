package io.github.lene.optix

import io.github.lene.optix.ImageValidation.getRGBAt
import io.github.lene.optix.ThresholdConstants.TEST_IMAGE_SIZE
import menger.common.Color
import menger.common.Light
import menger.common.TriangleMeshData
import menger.common.Vector
import menger.common.x
import menger.common.y
import menger.common.z
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

// The direct-light highlight of opaque dielectrics (optix-jni#105): GGX with Schlick Fresnel,
// F0 = 0.08 * specular, driven by a material's `specular` and `roughness`. Each scene is rendered
// twice, in a material and in Matte of the same colour; the highlight is what the first adds, so
// it may brighten a pixel and must never darken one (`excess` fails on that). Colours are dark
// enough that the diffuse share stays well below saturation.
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

  /** Per pixel, how much brighter `image` is than `base`. A highlight only adds light, so a
    * pixel that got darker (a colour that wrapped past 255, light lost) fails here. */
  private def excess(image: Array[Byte], base: Array[Byte]): IndexedSeq[Int] =
    val signed =
      for
        y <- 0 until Size.height
        x <- 0 until Size.width
      yield getRGBAt(image, Size, x, y).brightness - getRGBAt(base, Size, x, y).brightness
    withClue("the highlight darkened a pixel (darkest difference): ") { signed.min should be >= 0 }
    signed

  private case class Highlight(peak: Int, area: Int, centreX: Double, centreY: Double)

  /** The pixels at least half as bright over the base as the brightest one. */
  private def highlight(gain: IndexedSeq[Int]): Highlight =
    val peak = gain.max
    require(peak > 0, "no highlight: the image is not brighter than its base anywhere")
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

  private def maxBrightness(image: Array[Byte]): Int =
    (0 until Size.width * Size.height).map(ImageValidation.getRGB(image, _).max).max

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
  private val SmoothRoughness = 0.2f
  // The rough-vs-smooth comparison: the strongest specular, a dim light, so that neither
  // highlight clips (smooth peaks near 160 of 255): a clipped peak measures the clipping.
  private val GlossIntensity = 0.3f
  private val SmoothGloss = 0.3f
  private val RoughGloss = 0.6f
  private val ClipLevel = 250

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
      val image = renderFrontLitSphere(glossy, GlossIntensity)
      withClue("a clipped highlight measures the clipping, not its width: ") {
        maxBrightness(image) should be < ClipLevel
      }
      highlight(excess(image, renderFrontLitSphere(Material.matte(DarkGrey), GlossIntensity)))
    val smooth = spotAt(SmoothGloss)
    val rough = spotAt(RoughGloss)
    rough.peak should be < smooth.peak
    rough.area should be > smooth.area

  // A point light's highlight scales with its inverse-square attenuation: the sphere's front
  // point is 2.4 from a light at the camera, so intensity 6 leaves 6 / 6.76 of a unit light.
  private val PointLightIntensity = 6.0f

  it should "show a point light's highlight centred on its mirror direction" in:
    def renderPointLit(material: Material): Array[Byte] =
      render:
        renderer.setCamera(FrontCamera, Origin, YUp, CameraFov)
        renderer.setLights(Array(Light.Point(FrontCamera, intensity = PointLightIntensity)))
        val _ = renderer.addSphereInstance(
          sphereTransform(0.0f, 0.0f, 0.0f, SphereRadius), material
        )
    val spot = highlight(excess(renderPointLit(Plastic), renderPointLit(Matte)))
    spot.peak.toDouble should be > MinHighlightGain
    spot.centreX shouldBe (Size.width / 2.0) +- CentroidTolerancePixels
    spot.centreY shouldBe (Size.height / 2.0) +- CentroidTolerancePixels

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

  // ----- The highlight's magnitude, at the mirror point, against the analytic value -----
  // There H = N, so GGX reduces to F * 0.25 / (alpha^2 * sqrt(c^2 (1 - alpha^2) + alpha^2)) with
  // c = N.V = N.L and alpha = roughness^2; the pixel gains 255 * 0.7 * intensity * that (0.7 is
  // the share direct light has next to the 0.3 ambient).

  private val MirrorCameraDistance = 3.0f
  private val MagnitudeRoughness = 0.6f  // a wide lobe, so the pixels around the centre agree
  private val DirectLightShare = 0.7
  private val MagnitudeToleranceLevels = 3.0

  private def renderMirrorPlane(
      material: Material, cameraHeight: Float, intensity: Float
  ): Array[Byte] =
    render:
      renderer.setShadows(false)
      val eye = Vector[3](0.0f, cameraHeight, MirrorCameraDistance)
      renderer.setCamera(eye, Origin, YUp, CameraFov)
      renderer.setLight(Vector[3](0.0f, -cameraHeight, MirrorCameraDistance), intensity)
      val _ = renderer.addPlaneInstance(YUp, 0.0f, material)

  private def mirrorHighlightLevels(
      specular: Float, roughness: Float, intensity: Float, cosView: Double
  ): Double =
    val alphaSq = math.pow(roughness * roughness, 2)
    val f0 = 0.08 * specular
    val fresnel = f0 + (1 - f0) * math.pow(1 - cosView, 5)
    val ggx = 0.25 * fresnel / (alphaSq * math.sqrt(cosView * cosView * (1 - alphaSq) + alphaSq))
    255.0 * DirectLightShare * intensity * ggx

  private def checkMirrorHighlight(specular: Float, cameraHeight: Float, intensity: Float): Unit =
    val material = Material(Grey, ior = 1.0f, roughness = MagnitudeRoughness, specular = specular)
    val glossy = renderMirrorPlane(material, cameraHeight, intensity)
    val base = renderMirrorPlane(material.copy(specular = 0.0f), cameraHeight, intensity)
    val cosView = cameraHeight / math.hypot(cameraHeight, MirrorCameraDistance)
    centreBrightness(glossy) should be < ClipLevel.toDouble
    val expected = mirrorHighlightLevels(specular, MagnitudeRoughness, intensity, cosView)
    centreBrightness(glossy) - centreBrightness(base) shouldBe expected +- MagnitudeToleranceLevels

  it should "have the analytic magnitude at the mirror point" in:
    checkMirrorHighlight(specular = 0.5f, cameraHeight = 2.0f, intensity = 1.0f)

  // At a grazing view Schlick's Fresnel term is far from F0; this pins F90 = 1 (a smaller F90
  // lowers the highlight by a fifth here).
  it should "have the analytic magnitude at a grazing angle" in:
    checkMirrorHighlight(specular = 0.2f, cameraHeight = 0.4f, intensity = 0.1f)

  // ========== A surface given no material has no highlight ==========

  "A surface given no material" should "render a legacy plane like Matte of the same colour" in:
    def legacyPlane(add: => Unit): Array[Byte] =
      render:
        renderer.setCamera(PlaneCamera, Origin, YUp, CameraFov)
        renderer.setLight(MirrorLight, 1.0f)
        renderer.clearPlanes()
        add
    val withoutMaterial = legacyPlane(
      renderer.addPlaneSolidColor(1, positive = true, 0.0f, Grey.r, Grey.g, Grey.b)
    )
    withoutMaterial shouldEqual legacyPlane(
      renderer.addPlaneSolidColorWithMaterial(1, positive = true, 0.0f, Grey, Matte)
    )

  it should "render a single-object sphere like a Matte sphere instance" in:
    setSphereColor(Grey)
    renderer.setCamera(FrontCamera, Origin, YUp, CameraFov)
    renderer.setLight(FromCamera, 1.0f)
    val single = centreBrightness(renderImage(Size))
    centreBrightness(renderFrontLitSphere(Matte)) shouldBe single +- 2.0

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

  // `excess` fails where a highlight darkens a pixel, which is how a colour that wraps past 255
  // shows (the legacy plane's mirror point clips).
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

  // optix-jni#108: __intersection__cone reports a flank normal tilted toward the base instead of
  // the apex (hv/|hv| * cos - axis * sin uses the apex-to-hit direction where the radial one
  // belongs), so the highlight shows under a light from below, not from this one.
  it should "show the highlight where its flank mirrors the light" in:
    pendingUntilFixed:
      val gain = excess(renderCone(Plastic, ConeFlankLight), renderCone(Matte, ConeFlankLight))
      highlight(gain).peak.toDouble should be > MinHighlightGain

  // The cone takes shadows (optix-jni#109). With #108 only a light from below makes it show a
  // highlight, so the light shines up and a plate under the cone shadows all of it.
  private val ConeLightFromBelow = Vector[3](0.0f, 0.997f, 0.064f)

  private def renderConeUnderPlate(material: Material, plate: Boolean): Array[Byte] =
    render:
      renderer.setShadows(true)
      renderer.setCamera(FrontCamera, Origin, YUp, CameraFov)
      renderer.setLight(ConeLightFromBelow, 1.0f)
      val _ = renderer.addConeInstance(ConeApex, ConeBase, ConeRadius, material)
      if plate then addPlate()

  private def addPlate(): Unit =
    val _ = renderer.addCylinderInstance(
      Vector[3](0.0f, -0.8f, -0.1f), Vector[3](0.0f, -0.7f, -0.1f), PlateRadius, Matte
    )

  private val PlateRadius = 1.5f

  it should "show no highlight where the light is shadowed" in:
    def gain(plate: Boolean) =
      excess(renderConeUnderPlate(Plastic, plate), renderConeUnderPlate(Matte, plate))
    highlight(gain(plate = false)).peak.toDouble should be > MinHighlightGain
    gain(plate = true).max shouldBe 0

  // ========== A triangle face faded by coverage or alpha keeps its highlight ==========

  private def renderQuad(material: Material, coverage: Float): Array[Byte] =
    frontLit { m =>
      val _ = renderer.addTriangleMesh(quad)
      val id = renderer.addTriangleMeshInstance(Identity, m)
      renderer.setInstanceCoverage(id, coverage)
      id
    }(material)

  "A triangle face at half coverage" should "show half of Plastic's highlight over Matte" in:
    val gain = excess(renderQuad(Plastic, 0.5f), renderQuad(Matte, 0.5f))
    highlight(gain).peak.toDouble should be > MinHighlightGain / 2

  "A non-refractive triangle face at half alpha" should "show Plastic's highlight over Matte" in:
    def faded(specular: Float) =
      Material(Grey.copy(a = 0.5f), ior = 1.0f, roughness = 0.3f, specular = specular)
    val gain = excess(renderQuad(faded(0.5f), 1.0f), renderQuad(faded(0.0f), 1.0f))
    highlight(gain).peak.toDouble should be > MinHighlightGain / 2

  // ========== A plinth (cylinder) seen from above, as in CylinderSuite ==========

  private val PlinthRadius = 1.5f
  private val PlinthBottom = -0.2f
  private val TopDownCamera = Vector[3](0.0f, 4.0f, 0.0f)
  private val TopDownUp = Vector[3](0.0f, 0.0f, -1.0f)
  private val HighlightX = 0.6f
  // Reflects the view from the cap at (HighlightX, 0, 0) into the light.
  private val CapMirrorLight = Vector[3](-HighlightX, -TopDownCamera.y, 0.0f)

  private def addPlinth(material: Material): Unit =
    val _ = renderer.addCylinderInstance(
      Vector[3](0.0f, PlinthBottom, 0.0f), Origin, PlinthRadius, material
    )

  private def renderPlinthTopDown(material: Material): Array[Byte] =
    render:
      renderer.setCamera(TopDownCamera, Origin, TopDownUp, CameraFov)
      renderer.setLight(CapMirrorLight, 1.0f)
      addPlinth(material)

  "A plinth's top cap" should "show the highlight where it mirrors the light" in:
    val spot = highlight(excess(renderPlinthTopDown(Plastic), renderPlinthTopDown(Matte)))
    // The image is mirrored horizontally, so world +x is left of the centre (CylinderSuite).
    val halfWidthWorld = TopDownCamera.y * math.tan(math.toRadians(CameraFov / 2.0))
    val expectedColumn = Size.width / 2.0 * (1.0 - HighlightX / halfWidthWorld)
    spot.peak.toDouble should be > MinHighlightGain
    spot.centreX shouldBe expectedColumn +- CentroidTolerancePixels
    spot.centreY shouldBe (Size.height / 2.0) +- CentroidTolerancePixels

  // ========== A metal gets no dielectric highlight ==========

  // As in CylinderSuite: the cap seen through a glass sphere is reached at bounce depth 2, where
  // a cylinder shades diffuse. The light shines straight down, so the ray through the lens
  // centre meets the cap at its mirror direction: a dielectric shows a highlight there.
  private val LensCameraHeight = 5.0f
  private val LensHeight = 2.0f
  private val LensRadius = 0.7f
  private val LightStraightDown = Vector[3](0.0f, -1.0f, 0.0f)
  private val Steel = Material(Grey, ior = 1.0f, roughness = 0.3f, metallic = 1.0f, specular = 0.5f)

  private def renderPlinthThroughLens(material: Material): Array[Byte] =
    render:
      renderer.setShadows(false)
      renderer.setCamera(Vector[3](0.0f, LensCameraHeight, 0.0f), Origin, TopDownUp, CameraFov)
      renderer.setLight(LightStraightDown, 1.0f)
      addPlinth(material)
      val _ = renderer.addSphereInstance(
        sphereTransform(0.0f, LensHeight, 0.0f, LensRadius), Material.Glass
      )

  "A metal reached by a refracted ray" should "render the same with or without specular" in:
    centreBrightness(renderPlinthThroughLens(Plastic)) should be >
      centreBrightness(renderPlinthThroughLens(Matte)) + MinHighlightGain
    renderPlinthThroughLens(Steel) shouldEqual renderPlinthThroughLens(Steel.copy(specular = 0.0f))
