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
