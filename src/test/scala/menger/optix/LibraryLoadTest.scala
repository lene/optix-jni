package menger.optix

import com.typesafe.scalalogging.LazyLogging

object LibraryLoadTest extends LazyLogging {
  def main(args: Array[String]): Unit = {
    println("Starting library load test...")

    // Check if library is already loaded
    println(s"Library loaded status: ${OptiXRenderer.isLibraryLoaded}")

    // Try to create a renderer instance
    try {
      val renderer = new OptiXRenderer()
      println("Created OptiXRenderer instance successfully")

      // Try to call initialize
      try {
        val result = renderer.initialize()
        println(s"Initialize returned: $result")
      } catch {
        case e: UnsatisfiedLinkError =>
          println(s"UnsatisfiedLinkError calling initialize: ${e.getMessage}")
          e.printStackTrace()
      }
    } catch {
      case e: Exception =>
        println(s"Failed to create OptiXRenderer: ${e.getMessage}")
        e.printStackTrace()
    }
  }
}