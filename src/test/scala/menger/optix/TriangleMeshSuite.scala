package menger.optix

import menger.common.Color
import menger.common.TriangleMeshData
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import ThresholdConstants.*

class TriangleMeshSuite extends AnyFlatSpec with Matchers with RendererFixture:

  OptiXRenderer.isLibraryLoaded shouldBe true

  // Simple triangle mesh: a single triangle facing the camera
  private def singleTriangle: TriangleMeshData =
    // Vertices: position (3 floats) + normal (3 floats) = 6 floats per vertex
    // Triangle in XY plane at z=0, normal pointing toward camera (+Z)
    val vertices = Array[Float](
      -0.5f, -0.5f, 0.0f, 0.0f, 0.0f, 1.0f, // vertex 0: bottom-left
      0.5f, -0.5f, 0.0f, 0.0f, 0.0f, 1.0f, // vertex 1: bottom-right
      0.0f, 0.5f, 0.0f, 0.0f, 0.0f, 1.0f // vertex 2: top-center
    )
    val indices = Array[Int](0, 1, 2)
    TriangleMeshData(vertices, indices)

  // Two triangles forming a quad
  private def quadMesh: TriangleMeshData =
    val vertices = Array[Float](
      -0.5f, -0.5f, 0.0f, 0.0f, 0.0f, 1.0f, // vertex 0: bottom-left
      0.5f, -0.5f, 0.0f, 0.0f, 0.0f, 1.0f, // vertex 1: bottom-right
      0.5f, 0.5f, 0.0f, 0.0f, 0.0f, 1.0f, // vertex 2: top-right
      -0.5f, 0.5f, 0.0f, 0.0f, 0.0f, 1.0f // vertex 3: top-left
    )
    val indices = Array[Int](0, 1, 2, 0, 2, 3) // Two triangles
    TriangleMeshData(vertices, indices)

  // Cube mesh: 6 faces × 4 vertices = 24 vertices, 12 triangles
  private def cubeMesh(scale: Float = 1.5f): TriangleMeshData =
    val half = scale / 2.0f

    // Face definitions: (corners as offsets from center, normal)
    val faces = Seq(
      // +Z face (front)
      (Seq((-1, -1, 1), (1, -1, 1), (1, 1, 1), (-1, 1, 1)), (0.0f, 0.0f, 1.0f)),
      // -Z face (back)
      (Seq((1, -1, -1), (-1, -1, -1), (-1, 1, -1), (1, 1, -1)), (0.0f, 0.0f, -1.0f)),
      // +X face (right)
      (Seq((1, -1, 1), (1, -1, -1), (1, 1, -1), (1, 1, 1)), (1.0f, 0.0f, 0.0f)),
      // -X face (left)
      (Seq((-1, -1, -1), (-1, -1, 1), (-1, 1, 1), (-1, 1, -1)), (-1.0f, 0.0f, 0.0f)),
      // +Y face (top)
      (Seq((-1, 1, 1), (1, 1, 1), (1, 1, -1), (-1, 1, -1)), (0.0f, 1.0f, 0.0f)),
      // -Y face (bottom)
      (Seq((-1, -1, -1), (1, -1, -1), (1, -1, 1), (-1, -1, 1)), (0.0f, -1.0f, 0.0f))
    )

    // Generate vertices and indices functionally
    val (vertices, indices) = faces.zipWithIndex.foldLeft((Seq.empty[Float], Seq.empty[Int])) {
      case ((verts, inds), ((corners, (nx, ny, nz)), faceIdx)) =>
        val faceVerts = corners.flatMap { case (dx, dy, dz) =>
          Seq(dx * half, dy * half, dz * half, nx, ny, nz)
        }
        val base = faceIdx * 4
        val faceInds = Seq(base, base + 1, base + 2, base, base + 2, base + 3)
        (verts ++ faceVerts, inds ++ faceInds)
    }

    TriangleMeshData(vertices.toArray, indices.toArray)

  "Triangle mesh API" should "report no mesh by default" in:
    renderer.hasTriangleMesh() shouldBe false

  it should "report mesh present after setting" in:
    renderer.setTriangleMesh(singleTriangle)
    renderer.hasTriangleMesh() shouldBe true

  it should "report no mesh after clearing" in:
    renderer.setTriangleMesh(singleTriangle)
    renderer.hasTriangleMesh() shouldBe true
    renderer.clearTriangleMesh()
    renderer.hasTriangleMesh() shouldBe false

  it should "allow setting mesh color" in:
    renderer.setTriangleMesh(singleTriangle)
    noException should be thrownBy:
      renderer.setTriangleMeshColor(Color(1.0f, 0.0f, 0.0f))

  it should "allow setting mesh IOR" in:
    renderer.setTriangleMesh(singleTriangle)
    noException should be thrownBy:
      renderer.setTriangleMeshIOR(1.5f)

  it should "handle replacing mesh" in:
    renderer.setTriangleMesh(singleTriangle)
    renderer.hasTriangleMesh() shouldBe true
    renderer.setTriangleMesh(quadMesh)
    renderer.hasTriangleMesh() shouldBe true

  "Triangle mesh data validation" should "validate vertex count" in:
    val mesh = singleTriangle
    mesh.numVertices shouldBe 3

  it should "validate triangle count" in:
    val mesh = singleTriangle
    mesh.numTriangles shouldBe 1

  it should "handle quad mesh correctly" in:
    val mesh = quadMesh
    mesh.numVertices shouldBe 4
    mesh.numTriangles shouldBe 2

  it should "reject invalid vertex array length" in:
    an[IllegalArgumentException] should be thrownBy:
      TriangleMeshData(Array(1.0f, 2.0f, 3.0f), Array(0))

  it should "reject invalid index array length" in:
    an[IllegalArgumentException] should be thrownBy:
      TriangleMeshData(
        Array[Float](0, 0, 0, 0, 0, 1, 1, 0, 0, 0, 0, 1),
        Array(0, 1) // Not multiple of 3
      )

  "Triangle mesh rendering" should "render without crashing" in:
    renderer.setTriangleMesh(singleTriangle)
    renderer.setTriangleMeshColor(Color(0.0f, 1.0f, 0.0f))
    val result = renderer.render(TEST_IMAGE_SIZE)
    result.isDefined shouldBe true

  it should "produce valid image dimensions" in:
    renderer.setTriangleMesh(singleTriangle)
    renderer.setTriangleMeshColor(Color(1.0f, 0.0f, 0.0f))
    val imageData = renderer.render(TEST_IMAGE_SIZE).get
    imageData.length shouldBe TEST_IMAGE_SIZE.width * TEST_IMAGE_SIZE.height * 4

  it should "work with transparent material" in:
    renderer.setTriangleMesh(singleTriangle)
    renderer.setTriangleMeshColor(Color(0.9f, 0.9f, 1.0f, 0.5f))
    renderer.setTriangleMeshIOR(1.5f)
    val result = renderer.render(TEST_IMAGE_SIZE)
    result.isDefined shouldBe true

  // ========== Glass Cube Tests (Task 5.3) ==========

  "Glass cube rendering" should "render with refraction" in:
    renderer.setTriangleMesh(cubeMesh())
    renderer.setTriangleMeshColor(Color(0.9f, 0.9f, 1.0f, 0.3f)) // Light blue glass
    renderer.setTriangleMeshIOR(1.5f) // Glass IOR
    renderer.setPlane(1, true, -2.0f)

    val result = renderer.render(TEST_IMAGE_SIZE)
    result.isDefined shouldBe true
    result.get.length shouldBe TEST_IMAGE_SIZE.width * TEST_IMAGE_SIZE.height * 4

  it should "look different with different IOR values" in:
    renderer.setTriangleMesh(cubeMesh())
    renderer.setTriangleMeshColor(Color(1.0f, 1.0f, 1.0f, 0.2f))
    renderer.setPlane(1, true, -2.0f)

    renderer.setTriangleMeshIOR(1.0f) // No refraction
    val ior1 = renderer.render(TEST_IMAGE_SIZE).get

    renderer.setTriangleMeshIOR(1.5f) // Glass
    val ior15 = renderer.render(TEST_IMAGE_SIZE).get

    renderer.setTriangleMeshIOR(2.4f) // Diamond
    val ior24 = renderer.render(TEST_IMAGE_SIZE).get

    // All should be different
    ior1 should not equal ior15
    ior15 should not equal ior24

  it should "show colored absorption for tinted glass cube" in:
    renderer.setTriangleMesh(cubeMesh())
    renderer.setTriangleMeshIOR(1.5f)
    renderer.setPlane(1, true, -2.0f)

    // Clear glass
    renderer.setTriangleMeshColor(Color(1.0f, 1.0f, 1.0f, 0.3f))
    val clear = renderer.render(TEST_IMAGE_SIZE).get

    // Red tinted glass
    renderer.setTriangleMeshColor(Color(1.0f, 0.3f, 0.3f, 0.5f))
    val red = renderer.render(TEST_IMAGE_SIZE).get

    // Blue tinted glass
    renderer.setTriangleMeshColor(Color(0.3f, 0.3f, 1.0f, 0.5f))
    val blue = renderer.render(TEST_IMAGE_SIZE).get

    // All should be visually different
    clear should not equal red
    red should not equal blue

  // Shadow support for triangle meshes requires IAS (Instance Acceleration Structure)
  // to trace shadow rays against both sphere and triangle GAS. This is planned for Sprint 6
  // (SPRINT_6_PLAN.md - Step 6.1: IAS Infrastructure). Currently shadow rays only trace
  // against the sphere GAS, so triangle mesh shadows are not visible.
  it should "cast shadows on the plane" ignore:
    renderer.setTriangleMesh(cubeMesh(scale = 1.0f))
    renderer.setTriangleMeshColor(Color(0.8f, 0.8f, 0.8f)) // Opaque gray
    renderer.setTriangleMeshIOR(1.0f)
    renderer.setPlane(1, true, -1.5f)

    renderer.setShadows(true)
    val withShadows = renderer.render(TEST_IMAGE_SIZE).get

    renderer.setShadows(false)
    val withoutShadows = renderer.render(TEST_IMAGE_SIZE).get

    // Images should differ (shadow visible)
    withShadows should not equal withoutShadows

  it should "render opaque cube producing a valid image" in:
    renderer.setTriangleMesh(cubeMesh())
    renderer.setTriangleMeshColor(Color(0.8f, 0.2f, 0.2f)) // Red opaque
    renderer.setTriangleMeshIOR(1.0f)

    val result = renderer.render(TEST_IMAGE_SIZE).get

    // Verify the image has non-background pixels (cube is visible)
    val nonBackgroundPixels = (0 until (TEST_IMAGE_SIZE.width * TEST_IMAGE_SIZE.height)).count { i =>
      val offset = i * 4
      val r = result(offset) & 0xFF
      val g = result(offset + 1) & 0xFF
      val b = result(offset + 2) & 0xFF
      // Background is usually dark blue/gray, cube should have visible red component
      r > 50
    }

    // At least some portion of the image should show the cube (red pixels)
    nonBackgroundPixels should be > 100
