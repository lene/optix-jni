package menger.optix

import menger.common.Color

case class Material(
    color: Color,
    ior: Float = 1.0f,
    roughness: Float = 0.5f,
    metallic: Float = 0.0f,
    specular: Float = 0.5f,
    emission: Float = 0.0f,
    baseColorTexture: Option[Int] = None,
    normalTexture: Option[Int] = None,
    roughnessTexture: Option[Int] = None
):
  def withColorOpt(c: Option[Color]): Material = c.fold(this)(v => copy(color = v))
  def withIorOpt(i: Option[Float]): Material = i.fold(this)(v => copy(ior = v))
  def withRoughnessOpt(r: Option[Float]): Material = r.fold(this)(v => copy(roughness = v))
  def withMetallicOpt(m: Option[Float]): Material = m.fold(this)(v => copy(metallic = v))
  def withSpecularOpt(s: Option[Float]): Material = s.fold(this)(v => copy(specular = v))
  def withEmissionOpt(e: Option[Float]): Material = e.fold(this)(v => copy(emission = v))

object Material:

  private val White = Color(1.0f, 1.0f, 1.0f, 1.0f)

  // Dielectric presets (transparent materials with refraction)
  val Glass = Material(
    color = White.copy(a = 0.02f),
    ior = 1.5f,
    roughness = 0.0f,
    metallic = 0.0f,
    specular = 1.0f,
    emission = 0.0f
  )

  val Water = Material(
    color = White.copy(a = 0.02f),
    ior = 1.33f,
    roughness = 0.0f,
    metallic = 0.0f,
    specular = 1.0f,
    emission = 0.0f
  )

  val Diamond = Material(
    color = White.copy(a = 0.02f),
    ior = 2.42f,
    roughness = 0.0f,
    metallic = 0.0f,
    specular = 1.0f,
    emission = 0.0f
  )

  // Metal presets (colored reflections, no refraction)
  val Chrome = Material(
    color = Color(0.9f, 0.9f, 0.9f, 1.0f),
    ior = 1.0f,
    roughness = 0.0f,
    metallic = 1.0f,
    specular = 1.0f,
    emission = 0.0f
  )

  val Gold = Material(
    color = Color(1.0f, 0.84f, 0.0f, 1.0f),
    ior = 1.0f,
    roughness = 0.1f,
    metallic = 1.0f,
    specular = 1.0f,
    emission = 0.0f
  )

  val Copper = Material(
    color = Color(0.72f, 0.45f, 0.20f, 1.0f),
    ior = 1.0f,
    roughness = 0.2f,
    metallic = 1.0f,
    specular = 1.0f,
    emission = 0.0f
  )

  // Semi-transparent film/parchment materials
  val Film = Material(
    color = White.copy(a = 0.2f),  // 20% opaque (80% transparent)
    ior = 1.1f,
    roughness = 0.1f,
    metallic = 0.0f,
    specular = 0.5f,
    emission = 0.0f
  )

  val Parchment = Material(
    color = Color(245f/255f, 222f/255f, 179f/255f, 0.4f),  // Beige/tan, 40% opaque
    ior = 1.2f,
    roughness = 0.4f,
    metallic = 0.0f,
    specular = 0.3f,
    emission = 0.0f
  )

  // Factory methods for custom colors
  def matte(color: Color): Material =
    Material(color, ior = 1.0f, roughness = 1.0f, metallic = 0.0f, specular = 0.0f)

  def plastic(color: Color): Material =
    Material(color, ior = 1.5f, roughness = 0.3f, metallic = 0.0f, specular = 0.5f)

  def metal(color: Color): Material =
    Material(color, ior = 1.0f, roughness = 0.1f, metallic = 1.0f, specular = 1.0f)

  def glass(color: Color): Material =
    Material(color.copy(a = 0.02f), ior = 1.5f, roughness = 0.0f, metallic = 0.0f, specular = 1.0f)

  // Lookup by name (case-insensitive)
  def fromName(name: String): Option[Material] =
    name.toLowerCase match
      case "glass"     => Some(Glass)
      case "water"     => Some(Water)
      case "diamond"   => Some(Diamond)
      case "chrome"    => Some(Chrome)
      case "gold"      => Some(Gold)
      case "copper"    => Some(Copper)
      case "film"      => Some(Film)
      case "parchment" => Some(Parchment)
      case "metal"     => Some(metal(White))
      case "plastic"   => Some(plastic(White))
      case "matte"     => Some(matte(White))
      case _           => None

  // All known preset names (for validation/help text)
  val presetNames: Set[String] =
    Set("glass", "water", "diamond", "chrome", "gold", "copper", "film", "parchment", "metal", "plastic", "matte")
