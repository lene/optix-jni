package menger.optix

import menger.common.Color
import menger.common.Vector
import java.nio.file.{Files, Paths}

object FarSphereVisualization:

  def savePPM(filename: String, pixels: Array[Byte], width: Int, height: Int): Unit =
    val header = s"P6\n$width $height\n255\n".getBytes
    val rgb = new Array[Byte](width * height * 3)

    // Convert RGBA to RGB
    var i = 0
    var j = 0
    while i < pixels.length do
      rgb(j) = pixels(i)       // R
      rgb(j + 1) = pixels(i + 1) // G
      rgb(j + 2) = pixels(i + 2) // B
      i += 4
      j += 3

    Files.write(Paths.get(filename), header ++ rgb)
    println(s"Saved: $filename")

  def main(args: Array[String]): Unit =
    // Ensure library is loaded by accessing companion object
    require(OptiXRenderer.isLibraryLoaded, "OptiX native library failed to load")

    val renderer = new OptiXRenderer()
    renderer.initialize()

    // Setup with HALF the distance: 2 units apart instead of 4
    // sphereY = 1.0f, planeY = -1.0f (2 units apart)
    renderer.setSphere(Vector[3](0.0f, 1.0f, 0.0f), 0.5f)
    renderer.setSphereColor(Color(0.75f, 0.75f, 0.75f, 1.0f))  // Opaque gray
    renderer.setIOR(1.5f)
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
    savePPM("half_distance_shadows_off.ppm", pixelsOff, width, height)

    // Render with shadows ON
    renderer.setShadows(true)
    val pixelsOn = renderer.render(width, height).get
    savePPM("half_distance_shadows_on.ppm", pixelsOn, width, height)

    println("Done! Convert to PNG with:")
    println("  convert half_distance_shadows_off.ppm half_distance_shadows_off.png")
    println("  convert half_distance_shadows_on.ppm half_distance_shadows_on.png")
