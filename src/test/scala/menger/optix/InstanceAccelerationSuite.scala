package menger.optix

import menger.common.Color
import menger.common.Const
import menger.common.Vector

import com.typesafe.scalalogging.LazyLogging
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.tagobjects.Slow

import ColorConstants.{OPAQUE_BLUE, OPAQUE_GREEN, OPAQUE_RED, Red, Green}
import ThresholdConstants.{TEST_IMAGE_SIZE, MIN_FPS}

/** Tests for Instance Acceleration Structure (IAS) multi-object support.
  */
class InstanceAccelerationSuite extends AnyFlatSpec
    with Matchers
    with LazyLogging
    with RendererFixture:

  OptiXRenderer.isLibraryLoaded shouldBe true

  "IAS mode" should "be disabled by default" in:
    renderer.isIASMode() shouldBe false

  it should "be enabled when setIASMode(true) is called" in:
    renderer.setIASMode(true)
    renderer.isIASMode() shouldBe true

  it should "be disabled when setIASMode(false) is called" in:
    renderer.setIASMode(true)
    renderer.setIASMode(false)
    renderer.isIASMode() shouldBe false

  "Instance count" should "be zero initially" in:
    renderer.getInstanceCount() shouldBe 0

  it should "increase when sphere instances are added" in:
    val id = renderer.addSphereInstance(Vector[3](0.0f, 0.0f, 0.0f), OPAQUE_RED, 1.5f)
    id shouldBe defined
    renderer.getInstanceCount() shouldBe 1

  it should "increase for each added instance" in:
    renderer.addSphereInstance(Vector[3](0.0f, 0.0f, 0.0f), OPAQUE_RED, 1.5f)
    renderer.addSphereInstance(Vector[3](1.0f, 0.0f, 0.0f), OPAQUE_GREEN, 1.5f)
    renderer.addSphereInstance(Vector[3](-1.0f, 0.0f, 0.0f), OPAQUE_BLUE, 1.5f)
    renderer.getInstanceCount() shouldBe 3

  "addSphereInstance" should "return sequential IDs starting from 0" in:
    val id0 = renderer.addSphereInstance(Vector[3](0.0f, 0.0f, 0.0f), OPAQUE_RED, 1.5f)
    val id1 = renderer.addSphereInstance(Vector[3](1.0f, 0.0f, 0.0f), OPAQUE_GREEN, 1.5f)
    val id2 = renderer.addSphereInstance(Vector[3](-1.0f, 0.0f, 0.0f), OPAQUE_BLUE, 1.5f)

    id0 shouldBe Some(0)
    id1 shouldBe Some(1)
    id2 shouldBe Some(2)

  it should "enable IAS mode automatically" in:
    renderer.isIASMode() shouldBe false
    renderer.addSphereInstance(Vector[3](0.0f, 0.0f, 0.0f), OPAQUE_RED, 1.5f)
    renderer.isIASMode() shouldBe true

  it should "accept custom 4x3 transform matrix" in:
    val transform = Array(
      2.0f, 0.0f, 0.0f, 1.0f,  // scale X by 2, translate X by 1
      0.0f, 2.0f, 0.0f, 0.0f,  // scale Y by 2
      0.0f, 0.0f, 2.0f, 0.0f   // scale Z by 2
    )
    val id = renderer.addSphereInstance(transform, OPAQUE_RED, 1.5f)
    id shouldBe defined

  it should "reject transform arrays with wrong size" in:
    val wrongSize = Array(1.0f, 0.0f, 0.0f)  // Only 3 elements
    an[IllegalArgumentException] should be thrownBy:
      renderer.addSphereInstance(wrongSize, OPAQUE_RED, 1.5f)

  "removeInstance" should "reduce instance count" in:
    val id0 = renderer.addSphereInstance(Vector[3](0.0f, 0.0f, 0.0f), OPAQUE_RED, 1.5f)
    val id1 = renderer.addSphereInstance(Vector[3](1.0f, 0.0f, 0.0f), OPAQUE_GREEN, 1.5f)
    renderer.getInstanceCount() shouldBe 2

    renderer.removeInstance(id0.get)
    renderer.getInstanceCount() shouldBe 1

  it should "not crash when removing non-existent instance" in:
    noException should be thrownBy:
      renderer.removeInstance(999)

  "clearAllInstances" should "reset instance count to zero" in:
    renderer.addSphereInstance(Vector[3](0.0f, 0.0f, 0.0f), OPAQUE_RED, 1.5f)
    renderer.addSphereInstance(Vector[3](1.0f, 0.0f, 0.0f), OPAQUE_GREEN, 1.5f)
    renderer.addSphereInstance(Vector[3](-1.0f, 0.0f, 0.0f), OPAQUE_BLUE, 1.5f)
    renderer.getInstanceCount() shouldBe 3

    renderer.clearAllInstances()
    renderer.getInstanceCount() shouldBe 0

  it should "disable IAS mode" in:
    renderer.addSphereInstance(Vector[3](0.0f, 0.0f, 0.0f), OPAQUE_RED, 1.5f)
    renderer.isIASMode() shouldBe true

    renderer.clearAllInstances()
    renderer.isIASMode() shouldBe false

  // =====================
  // IAS Rendering Tests
  // =====================

  "IAS rendering" should "render a single sphere instance" taggedAs (Slow) in:
    renderer.addSphereInstance(Vector[3](0.0f, 0.0f, 0.0f), OPAQUE_RED, 1.5f)
    val img = renderImage(TEST_IMAGE_SIZE)

    img.length shouldBe ImageValidation.imageByteSize(TEST_IMAGE_SIZE)
    img.count(_ != 0) should be > 0

  it should "render multiple sphere instances" taggedAs (Slow) in:
    renderer.addSphereInstance(Vector[3](-1.5f, 0.0f, 0.0f), OPAQUE_RED, 1.5f)
    renderer.addSphereInstance(Vector[3](1.5f, 0.0f, 0.0f), OPAQUE_BLUE, 1.5f)
    val img = renderImage(TEST_IMAGE_SIZE)

    img.length shouldBe ImageValidation.imageByteSize(TEST_IMAGE_SIZE)
    img.count(_ != 0) should be > 0

  it should "render three sphere instances with distinct colors" taggedAs (Slow) in:
    renderer.addSphereInstance(Vector[3](-2.0f, 0.0f, 0.0f), OPAQUE_RED, 1.5f)
    renderer.addSphereInstance(Vector[3](0.0f, 0.0f, 0.0f), OPAQUE_GREEN, 1.5f)
    renderer.addSphereInstance(Vector[3](2.0f, 0.0f, 0.0f), OPAQUE_BLUE, 1.5f)
    val img = renderImage(TEST_IMAGE_SIZE)

    img.length shouldBe ImageValidation.imageByteSize(TEST_IMAGE_SIZE)
    img.count(_ != 0) should be > 0

  it should "handle repeated renders without CUDA errors" taggedAs (Slow) in:
    // This test specifically verifies the fix for CUDA error 700
    // which occurred when rendering multiple times with IAS
    renderer.addSphereInstance(Vector[3](0.0f, 0.0f, 0.0f), OPAQUE_RED, 1.5f)

    // Render 5 times to stress test the fix
    for i <- 1 to 5 do
      val img = renderImage(TEST_IMAGE_SIZE)
      img.length shouldBe ImageValidation.imageByteSize(TEST_IMAGE_SIZE)
      img.count(_ != 0) should be > 0

  // ================================
  // Triangle Mesh Instance Tests
  // ================================

  "addTriangleMeshInstance" should "return sequential IDs" in:
    // First set up a base triangle mesh
    val mesh = TestUtilities.createUnitCubeMesh()
    renderer.setTriangleMesh(mesh)

    val id0 = renderer.addTriangleMeshInstance(Vector[3](0.0f, 0.0f, 0.0f), OPAQUE_RED, 1.5f)
    val id1 = renderer.addTriangleMeshInstance(Vector[3](1.0f, 0.0f, 0.0f), OPAQUE_GREEN, 1.5f)
    val id2 = renderer.addTriangleMeshInstance(Vector[3](-1.0f, 0.0f, 0.0f), OPAQUE_BLUE, 1.5f)

    id0 shouldBe Some(0)
    id1 shouldBe Some(1)
    id2 shouldBe Some(2)

  it should "enable IAS mode automatically" in:
    val mesh = TestUtilities.createUnitCubeMesh()
    renderer.setTriangleMesh(mesh)

    renderer.isIASMode() shouldBe false
    renderer.addTriangleMeshInstance(Vector[3](0.0f, 0.0f, 0.0f), OPAQUE_RED, 1.5f)
    renderer.isIASMode() shouldBe true

  it should "increase instance count" in:
    val mesh = TestUtilities.createUnitCubeMesh()
    renderer.setTriangleMesh(mesh)

    renderer.addTriangleMeshInstance(Vector[3](0.0f, 0.0f, 0.0f), OPAQUE_RED, 1.5f)
    renderer.addTriangleMeshInstance(Vector[3](1.0f, 0.0f, 0.0f), OPAQUE_GREEN, 1.5f)
    renderer.getInstanceCount() shouldBe 2

  it should "fail if no triangle mesh is set" in:
    val result = renderer.addTriangleMeshInstance(Vector[3](0.0f, 0.0f, 0.0f), OPAQUE_RED, 1.5f)
    result shouldBe None

  "Triangle mesh IAS rendering" should "render a single cube instance" taggedAs (Slow) in:
    val mesh = TestUtilities.createUnitCubeMesh()
    renderer.setTriangleMesh(mesh)
    renderer.addTriangleMeshInstance(Vector[3](0.0f, 0.0f, 0.0f), OPAQUE_RED, 1.5f)

    val img = renderImage(TEST_IMAGE_SIZE)
    img.length shouldBe ImageValidation.imageByteSize(TEST_IMAGE_SIZE)
    img.count(_ != 0) should be > 0

  it should "render multiple cube instances" taggedAs (Slow) in:
    val mesh = TestUtilities.createUnitCubeMesh()
    renderer.setTriangleMesh(mesh)
    renderer.addTriangleMeshInstance(Vector[3](-1.5f, 0.0f, 0.0f), OPAQUE_RED, 1.5f)
    renderer.addTriangleMeshInstance(Vector[3](1.5f, 0.0f, 0.0f), OPAQUE_BLUE, 1.5f)

    val img = renderImage(TEST_IMAGE_SIZE)
    img.length shouldBe ImageValidation.imageByteSize(TEST_IMAGE_SIZE)
    img.count(_ != 0) should be > 0

  // ================================
  // Mixed Instance Tests
  // ================================

  "Instance count" should "track both sphere and triangle mesh instances" in:
    // Add sphere instances
    renderer.addSphereInstance(Vector[3](0.0f, 0.0f, 0.0f), OPAQUE_RED, 1.5f)
    renderer.addSphereInstance(Vector[3](1.0f, 0.0f, 0.0f), OPAQUE_GREEN, 1.5f)
    renderer.getInstanceCount() shouldBe 2

    // Add triangle mesh instances
    val mesh = TestUtilities.createUnitCubeMesh()
    renderer.setTriangleMesh(mesh)
    renderer.addTriangleMeshInstance(Vector[3](2.0f, 0.0f, 0.0f), OPAQUE_BLUE, 1.5f)
    renderer.getInstanceCount() shouldBe 3

  "clearAllInstances" should "clear both sphere and triangle mesh instances" in:
    renderer.addSphereInstance(Vector[3](0.0f, 0.0f, 0.0f), OPAQUE_RED, 1.5f)

    val mesh = TestUtilities.createUnitCubeMesh()
    renderer.setTriangleMesh(mesh)
    renderer.addTriangleMeshInstance(Vector[3](1.0f, 0.0f, 0.0f), OPAQUE_GREEN, 1.5f)

    renderer.getInstanceCount() shouldBe 2
    renderer.clearAllInstances()
    renderer.getInstanceCount() shouldBe 0
