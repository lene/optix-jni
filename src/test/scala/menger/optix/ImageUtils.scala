package menger.optix

/** Utility functions for image manipulation in tests */
object ImageUtils:

  /** Convert RGBA byte array to RGB byte array (discarding alpha channel)
    *
    * @param rgba
    *   RGBA pixel data (4 bytes per pixel)
    * @return
    *   RGB pixel data (3 bytes per pixel)
    */
  def rgbaToRgb(rgba: Array[Byte]): Array[Byte] =
    rgba.grouped(4).flatMap(chunk => Array(chunk(0), chunk(1), chunk(2))).toArray
