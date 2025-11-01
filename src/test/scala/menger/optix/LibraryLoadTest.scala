package menger.optix

import com.typesafe.scalalogging.LazyLogging

object LibraryLoadTest extends LazyLogging {
  def main(args: Array[String]): Unit = {

    // Check if library is already loaded
    logger.info(s"Library loaded status: ${OptiXRenderer.isLibraryLoaded}")

    // Try to create a renderer instance
    try {
      val renderer = new OptiXRenderer()
      logger.info("Created OptiXRenderer instance successfully")

      // Try to call initialize
      try {
        val result = renderer.initialize()
        logger.info(s"Initialize returned: $result")
      } catch {
        case e: UnsatisfiedLinkError =>
          logger.error(s"UnsatisfiedLinkError calling initialize: ${e.getMessage}")
          e.printStackTrace()
      }
    } catch {
      case e: Exception =>
        logger.error(s"Failed to create OptiXRenderer: ${e.getMessage}")
        e.printStackTrace()
    }
  }
}
