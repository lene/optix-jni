package menger.optix
import menger.common.Vector

import menger.common.ImageSize
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec


trait RendererFixture extends BeforeAndAfterEach:
  this: AnyFlatSpec =>

  
  private var rendererOpt: Option[OptiXRenderer] = None

  
  protected def renderer: OptiXRenderer = rendererOpt.getOrElse(
    throw new IllegalStateException("Renderer not initialized - test fixture may not have run beforeEach")
  )

  
  override def beforeEach(): Unit =
    super.beforeEach()
    // Explicitly trigger companion object initialization to ensure library is loaded
    // This prevents UnsatisfiedLinkError with testOnly on certain test classes
    require(OptiXRenderer.isLibraryLoaded, "OptiX native library failed to load")
    val r = new OptiXRenderer()
    r.initialize()
    rendererOpt = Some(r)
    setupDefaults()

  
  override def afterEach(): Unit =
    try
      rendererOpt.foreach(_.dispose())
      rendererOpt = None
    finally
      super.afterEach()

  
  protected def setupDefaults(): Unit =
    // Default camera: positioned at (0, 0.5, 3), looking at origin
    renderer.setCamera(
      Vector[3](0.0f, 0.5f, 3.0f),  // eye position
      Vector[3](0.0f, 0.0f, 0.0f),  // look-at point
      Vector[3](0.0f, 1.0f, 0.0f),  // up vector
      60.0f                     // field of view
    )

    // Default light: directional light from upper-right-front
    renderer.setLight(
      Vector[3](0.5f, 0.5f, -0.5f),  // direction
      1.0f                            // intensity
    )

    // Default sphere: centered at origin, radius 0.5
    renderer.setSphere(
      Vector[3](0.0f, 0.0f, 0.0f),  // position
      0.5f                           // radius
    )


  protected def setCameraEye(eye: Vector[3]): Unit =
    renderer.setCamera(
      eye,
      Vector[3](0.0f, 0.0f, 0.0f),
      Vector[3](0.0f, 1.0f, 0.0f),
      60.0f
    )

  
  protected def setSphereColor(red: Float, green: Float, blue: Float, alpha: Float): Unit =
    renderer.setSphereColor(red, green, blue, alpha)


  protected def renderImage(size: ImageSize): Array[Byte] =
    renderer.render(size).getOrElse:
      fail(s"Rendering failed - returned None for ${size.width}x${size.height}")
