package menger.optix

object ImageUtils:

  def rgbaToRgb(rgba: Array[Byte]): Array[Byte] =
    rgba.grouped(4).flatMap(chunk => Array(chunk(0), chunk(1), chunk(2))).toArray
