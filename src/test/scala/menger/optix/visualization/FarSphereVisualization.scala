package menger.optix.visualization

import menger.common.Color
import menger.common.Const
import menger.common.Vector
import menger.optix.OptiXRenderer
import menger.optix.TestUtilities

object FarSphereVisualization:

  def main(args: Array[String]): Unit =
    // Ensure library is loaded by accessing companion object
    require(OptiXRenderer.isLibraryLoaded, "OptiX native library failed to load")

    val renderer = new OptiXRenderer()
    renderer.initialize()

    // Setup with HALF the distance: 2 units apart instead of 4
    // sphereY = 1.0f, planeY = -1.0f (2 units apart)
    renderer.setSphere(Vector[3](0.0f, 1.0f, 0.0f), 0.5f)
    renderer.setSphereColor(Color(0.75f, 0.75f, 0.75f, 1.0f))  // Opaque gray
    renderer.setIOR(Const.iorGlass)
    renderer.setScale(1.0f)
    renderer.setPlane(1, true, -1.0f)  // Y-axis plane at -1.0
    renderer.setPlaneSolidColor(Color.LIGHT_GRAY)

    // Camera from setupShadowScene
    renderer.setCamera(
      Vector[3](0.0f, 0.0f, 5.0f),    // eye
      Vector[3](0.0f, -0.3f, 0.0f),  // lookAt
      Vector[3](0.0f, 1.0f, 0.0f),    // up
      45.0f                        // fov
    )

    // Default light from setupShadowScene
    renderer.setLight(Vector[3](0.5f, 0.5f, -0.5f), 1.0f)

    val width = 800
    val height = 600

    // Render with shadows OFF
    renderer.setShadows(false)
    val pixelsOff = renderer.render(width, height).get
    TestUtilities.savePNG("half_distance_shadows_off.png", pixelsOff, width, height)

    // Render with shadows ON
    renderer.setShadows(true)
    val pixelsOn = renderer.render(width, height).get
    TestUtilities.savePNG("half_distance_shadows_on.png", pixelsOn, width, height)

    println("Done!")
