package menger.optix

import com.typesafe.scalalogging.LazyLogging
import scala.util.{Try, Success, Failure}

/**
 * Test utility to verify OptiX native library loading.
 *
 * Uses functional Try/Success/Failure pattern.
 *
 * Run with: sbt "project optixJni" "runMain menger.optix.LibraryLoadTest"
 */
object LibraryLoadTest extends LazyLogging:

  def main(args: Array[String]): Unit =
    // Create renderer using functional Try
    val rendererResult = Try(new OptiXRenderer())

    rendererResult match
      case Success(renderer) =>
        // Try to call initialize
        val initResult = Try(renderer.initialize())

        initResult match
          case Success(result) =>
            logger.info(s"Initialize returned: $result")

          case Failure(e: UnsatisfiedLinkError) =>
            logger.error(s"UnsatisfiedLinkError calling initialize: ${e.getMessage}")
            logger.error("Stack trace:", e)

          case Failure(e) =>
            logger.error(s"Unexpected error calling initialize: ${e.getMessage}")
            logger.error("Stack trace:", e)

      case Failure(e) =>
        logger.error(s"Failed to create OptiXRenderer: ${e.getMessage}")
        logger.error("Stack trace:", e)

  /**
   * Alternative version using for-comprehension for cleaner composition.
   *
   * This demonstrates a more idiomatic functional approach where operations
   * are composed in a for-comprehension. If any step fails, the entire
   * chain short-circuits.
   */
  def runWithComposition(): Try[Boolean] =
    for
      renderer <- Try(new OptiXRenderer())
      result   <- Try(renderer.initialize())
    yield result

  /**
   * Version with explicit error handling and recovery.
   *
   * Demonstrates how to recover from specific errors and provide
   * default values or alternative actions.
   */
  def runWithRecovery(): Boolean =
    val result = (for
      renderer <- Try(new OptiXRenderer())
      result   <- Try(renderer.initialize())
    yield result).recoverWith {
      case e: UnsatisfiedLinkError =>
        logger.error(s"Library linking failed: ${e.getMessage}")
        Failure(e)
      case e =>
        logger.error(s"Unexpected error: ${e.getMessage}")
        Failure(e)
    }

    result match
      case Success(initialized) =>
        logger.info(s"Initialization successful: $initialized")
        initialized
      case Failure(e) =>
        logger.error(s"Initialization failed: ${e.getMessage}")
        false
