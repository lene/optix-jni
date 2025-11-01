package menger.optix

import com.typesafe.scalalogging.LazyLogging

object ErrorHandling extends LazyLogging:
  def errorExit(message: String): Unit =
    logger.error(message)
    System.exit(1)
