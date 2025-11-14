package menger.optix

import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec

/**
 * Test fixture providing OptiXRenderer lifecycle management.
 *
 * This trait eliminates boilerplate renderer initialization and disposal
 * code from test methods. It automatically:
 * - Creates a new OptiXRenderer before each test
 * - Initializes the renderer
 * - Disposes the renderer after each test (even if test fails)
 *
 * Usage:
 * {{{
 * class MyTest extends AnyFlatSpec with RendererFixture {
 *   "my test" should "do something" in {
 *     // renderer is already initialized and ready to use
 *     renderer.setCamera(...)
 *     renderer.setSphere(...)
 *     val imageData = renderer.render(800, 600)
 *     // disposal happens automatically
 *   }
 * }
 * }}}
 */
trait RendererFixture extends BeforeAndAfterEach:
  this: AnyFlatSpec =>

  /** The OptiXRenderer instance available to all tests */
  private var rendererOpt: Option[OptiXRenderer] = None

  /**
   * Provides access to the initialized renderer.
   * Tests can use this directly as if it were a regular field.
   */
  protected def renderer: OptiXRenderer = rendererOpt.getOrElse(
    throw new IllegalStateException("Renderer not initialized - test fixture may not have run beforeEach")
  )

  /**
   * Creates and initializes a new OptiXRenderer before each test.
   * Sets up default configuration (camera, light, sphere).
   */
  override def beforeEach(): Unit =
    super.beforeEach()
    val r = new OptiXRenderer()
    r.initialize()
    rendererOpt = Some(r)
    setupDefaults()

  /**
   * Disposes the renderer after each test to free GPU resources.
   * Always executes even if the test fails.
   */
  override def afterEach(): Unit =
    try
      rendererOpt.foreach(_.dispose())
      rendererOpt = None
    finally
      super.afterEach()

  /**
   * Sets up default camera, light, and sphere configuration.
   * Can be overridden in subclasses for custom defaults.
   */
  protected def setupDefaults(): Unit =
    // Default camera: positioned at (0, 0.5, 3), looking at origin
    renderer.setCamera(
      Array(0.0f, 0.5f, 3.0f),  // eye position
      Array(0.0f, 0.0f, 0.0f),  // look-at point
      Array(0.0f, 1.0f, 0.0f),  // up vector
      60.0f                     // field of view
    )

    // Default light: directional light from upper-right-front
    renderer.setLight(
      Array(0.5f, 0.5f, -0.5f),  // direction
      1.0f                        // intensity
    )

    // Default sphere: centered at origin, radius 0.5
    renderer.setSphere(
      0.0f, 0.0f, 0.0f,  // position (x, y, z)
      0.5f               // radius
    )

  /**
   * Helper method to set custom camera position while keeping other
   * defaults. Useful for tests that only need to change camera position.
   */
  protected def setCameraEye(x: Float, y: Float, z: Float): Unit =
    renderer.setCamera(
      Array(x, y, z),
      Array(0.0f, 0.0f, 0.0f),
      Array(0.0f, 1.0f, 0.0f),
      60.0f
    )

  /**
   * Helper method to set sphere color with clear parameter names.
   * Standard graphics convention: alpha = 0.0 (transparent) to 1.0 (opaque)
   */
  protected def setSphereColor(red: Float, green: Float, blue: Float, alpha: Float): Unit =
    renderer.setSphereColor(red, green, blue, alpha)

  /**
   * Helper method to render and unwrap the Option for test convenience.
   * Fails the test if rendering returns None.
   */
  protected def renderImage(width: Int, height: Int): Array[Byte] =
    renderer.render(width, height).getOrElse:
      fail(s"Rendering failed - returned None for ${width}x${height}")
