package io.github.lene.optix

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** optix-jni#41: when the projection kernel's results couldn't be synchronized or read back,
  * setProjectedMesh still registered the mesh and returned its index -- a mesh with untrusted
  * vertices and a stale AABB, with nothing for the caller to notice. */
class ProjectedMeshFailureSuite extends AnyFlatSpec with Matchers:

  private val quad = Array(
    -0.5f, -0.5f, 0f, 0f, 0.5f, -0.5f, 0f, 0f, 0.5f, 0.5f, 0f, 0f, -0.5f, 0.5f, 0f, 0f
  )

  private def upload(r: OptiXRenderer): Int =
    r.setProjectedMesh(quad, 4, null, 3f, 1.5f, 0f, 0f, 0f) // scalafix:ok DisableSyntax.null

  "setProjectedMesh" should "fail with code -2 and register nothing when the readback fails" in:
    assume(OptiXRenderer.isLibraryLoaded, "OptiX native library not available")
    val r = new OptiXRenderer()
    try
      r.initialize() shouldBe true
      val first = upload(r)
      r.failNextProjectionReadbackNative()
      intercept[IllegalArgumentException](upload(r)).getMessage should include("-2")
      upload(r) shouldBe first + 1
    finally r.dispose()
