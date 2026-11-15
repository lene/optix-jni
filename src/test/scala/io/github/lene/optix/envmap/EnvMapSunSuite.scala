package io.github.lene.optix.envmap

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Measuring the sun of an equirectangular map: synthetic maps with a patch at a known direction
  * (they carry CI, no GPU needed) and, when the Kiara maps are on disk, the values the method
  * gives on them. The convention is `EnvMapMapping.h`: u = 0.5 is +x, u = 0.75 is +z, v = 0 is +y.
  */
class EnvMapSunSuite extends AnyFlatSpec with Matchers:

  private val width = 512
  private val height = 256
  private val patchHalfWidth = 3  // 7 x 7 texels: more than the brightest 0.01 % (14 texels)
  private val texelAngle = 2.0 * math.Pi / width  // radians, at the equator

  private type Direction = (Double, Double, Double)

  private def texelDirection(col: Int, row: Int): Direction =
    val theta = (row + 0.5) / height * math.Pi
    val phi = ((col + 0.5) / width - 0.5) * 2.0 * math.Pi
    (math.sin(theta) * math.cos(phi), math.cos(theta), math.sin(theta) * math.sin(phi))

  private def solidAngle(row: Int): Double =
    math.sin((row + 0.5) / height * math.Pi) * (2.0 * math.Pi / width) * (math.Pi / height)

  /** The texel a unit direction falls in, by the inverse of the map's convention. */
  private def texelOf(d: Direction): (Int, Int) =
    val u = 0.5 + math.atan2(d._3, d._1) / (2.0 * math.Pi)
    val v = math.acos(d._2) / math.Pi
    (math.floorMod((u * width).toInt, width), math.min(height - 1, (v * height).toInt))

  private def normalised(d: Direction): Direction =
    val length = math.sqrt(d._1 * d._1 + d._2 * d._2 + d._3 * d._3)
    (d._1 / length, d._2 / length, d._3 / length)

  private final case class Scene(image: HdrImage, patchTexels: Seq[(Int, Int)], centre: Direction)

  /** A uniform sky of `sky` with a patch of `patch` (RGB) around the texel `toward` falls in. */
  private def scene(toward: Direction, patch: (Float, Float, Float), sky: Float = 0f): Scene =
    val (col, row) = texelOf(normalised(toward))
    val texels = for
      dc <- -patchHalfWidth to patchHalfWidth
      dr <- -patchHalfWidth to patchHalfWidth
    yield (math.floorMod(col + dc, width), row + dr)
    val inPatch = texels.toSet
    val rgb = Array.tabulate(width * height * 3): i =>
      val texel = i / 3
      val c = i % 3
      if inPatch.contains((texel % width, texel / width)) then Seq(patch._1, patch._2, patch._3)(c) else sky
    Scene(HdrImage(width, height, rgb), texels, texelDirection(col, row))

  private def angleBetween(a: Direction, b: Direction): Double =
    math.acos(math.min(1.0, a._1 * b._1 + a._2 * b._2 + a._3 * b._3))

  private def asDirection(sun: EnvMapSun): Direction =
    (sun.directionToSun(0).toDouble, sun.directionToSun(1).toDouble, sun.directionToSun(2).toDouble)

  private val directions: Seq[(String, Direction)] = Seq(
    "+x (the middle of the map)" -> (1.0, 0.0, 0.0),
    "+z (u = 0.75)" -> (0.0, 0.0, 1.0),
    "-z (u = 0.25)" -> (0.0, 0.0, -1.0),
    "-x (the seam of the map)" -> (-1.0, 0.0, 0.0),
    "above the horizon" -> (0.5, 0.7, 0.5),
    "below the horizon" -> (0.3, -0.6, -0.74),
    "high up" -> (0.0, 0.97, 0.24))

  for (name, toward) <- directions do
    s"The sun of a map with a patch toward $name" should "be found within a texel of the patch centre" in:
      val Scene(image, _, centre) = scene(toward, (500f, 500f, 500f))
      val sun = EnvMapSun.measure(image)
      val error = angleBetween(asDirection(sun), centre)
      info(f"direction error ${math.toDegrees(error)}%.3f degrees (a texel is ${math.toDegrees(texelAngle)}%.3f)")
      error should be < texelAngle
      sun.directionToSun.len shouldBe 1f +- 1e-5f

  "The irradiance of a sun" should "be its radiance times its solid angle, and the intensity that over pi" in:
    val Scene(image, texels, centre) = scene((0.5, 0.2, 0.84), (500f, 500f, 500f))
    val sun = EnvMapSun.measure(image)
    val expected = 500.0 * texels.map((_, row) => solidAngle(row)).sum  // cos about the sun is 1
    info(f"E = ${sun.irradiance(1)}%.4f, radiance * solid angle = $expected%.4f, half-angle ${sun.halfAngleDegrees}")
    sun.irradiance(0).toDouble shouldBe expected +- (0.01 * expected)
    sun.intensity.toDouble shouldBe expected / math.Pi +- (0.01 * expected / math.Pi)
    sun.halfAngleDegrees shouldBe 4.0f  // the patch is 7 texels (2.5 degrees) across: only 4 degrees holds it
    sun.powerShare shouldBe 1.0f +- 0.001f
    centre should not be (0.0, 0.0, 0.0)

  it should "be per channel, with the colour 1 in the strongest" in:
    val Scene(image, texels, _) = scene((0.0, 0.1, 1.0), (500f, 250f, 125f))
    val sun = EnvMapSun.measure(image)
    val strongest = 500.0 * texels.map((_, row) => solidAngle(row)).sum
    sun.irradiance(0).toDouble shouldBe strongest +- (0.01 * strongest)
    sun.irradiance(1).toDouble shouldBe strongest / 2 +- (0.01 * strongest)
    sun.irradiance(2).toDouble shouldBe strongest / 4 +- (0.01 * strongest)
    (sun.color.r, sun.color.g, sun.color.b) match
      case (r, g, b) =>
        r shouldBe 1f +- 1e-5f
        g shouldBe 0.5f +- 0.01f
        b shouldBe 0.25f +- 0.01f

  it should "count the sky inside the cone, and report the share of the map's power in the cone" in:
    val sky = 0.02f
    val Scene(image, texels, _) = scene((0.3, 0.5, -0.8), (500f, 500f, 500f), sky)
    val sun = EnvMapSun.measure(image)
    val halfAngle = math.toRadians(sun.halfAngleDegrees.toDouble)
    val patchSolidAngle = texels.map((_, row) => solidAngle(row)).sum
    val expected = (500.0 - sky) * patchSolidAngle + sky * math.Pi * math.pow(math.sin(halfAngle), 2)
    sun.irradiance(0).toDouble shouldBe expected +- (0.015 * expected)
    val coneSolidAngle = 2.0 * math.Pi * (1.0 - math.cos(halfAngle))
    val sunPower = (500.0 - sky) * patchSolidAngle + sky * coneSolidAngle
    val mapPower = (500.0 - sky) * patchSolidAngle + sky * 4.0 * math.Pi
    sun.powerShare.toDouble shouldBe sunPower / mapPower +- (0.02 * sunPower / mapPower)

  "The half-angle" should "be the smallest of 0.5, 1, 2 and 4 degrees that doubling does not grow by 0.5 %" in:
    // A disc of radius about 1 degree (a 3 x 3 patch is too small for the brightest 0.01 % in a
    // 512 x 256 map, so the map is larger): everything is inside 1 degree, so 1 is the answer.
    val w = 2048
    val h = 1024
    val centreCol = 700
    val centreRow = 400
    val discRadius = 0.9  // degrees
    val texelDegrees = 360.0 / w
    val rgb = Array.tabulate(w * h * 3): i =>
      val texel = i / 3
      val (dc, dr) = (texel % w - centreCol, texel / w - centreRow)
      if math.hypot(dc, dr) * texelDegrees <= discRadius then 100f else 0f
    val sun = EnvMapSun.measure(HdrImage(w, h, rgb))
    sun.halfAngleDegrees shouldBe 1.0f

  "A map without a distinct sun" should "be rejected: black" in:
    an[IllegalArgumentException] should be thrownBy
      EnvMapSun.measure(HdrImage(width, height, new Array[Float](width * height * 3)))

  it should "be rejected: the same everywhere" in:
    an[IllegalArgumentException] should be thrownBy
      EnvMapSun.measure(HdrImage(width, height, Array.fill(width * height * 3)(0.5f)))

  // The Kiara maps are local, untracked assets: this check runs where they are, else it is canceled.
  private val kiaraDirectories: Seq[Path] =
    (sys.env.get("MENGER_HDRI_DIR").toSeq ++ Seq("../menger/assets/hdri", "../../../menger/assets/hdri"))
      .map(Paths.get(_))

  private final case class Kiara(
      file: String, direction: Direction, irradiance: (Double, Double, Double), intensity: Double,
      halfAngle: Float, powerShare: Double)

  // Direction, irradiance, intensity and power share are those of the numpy reference script. It
  // decoded mantissa * 2^(e - 136) where stb_image (and so the renderer) decodes (mantissa + 0.5) *
  // 2^(e - 136), 0.3 % brighter, and used a fixed half-angle of 1, 1 and 2 degrees where the rule
  // (doubling adds under 0.5 %) picks 1, 2 and 4: a wider cone holds more of the glow, up to 1.6 %
  // more intensity (1.6 %) and blue (2.1 %) on the sunrise map. Hence 3 % here; the half-angles are
  // pinned exactly.
  private val kiaras = Seq(
    Kiara("kiara_4_mid-morning_4k.hdr", (0.6049, 0.5825, 0.5429), (13.293, 13.015, 13.536), 4.309, 1.0f, 0.8874),
    Kiara("kiara_3_morning_4k.hdr", (0.6911, 0.3684, 0.6218), (13.857, 13.581, 14.094), 4.486, 2.0f, 0.8497),
    Kiara("kiara_2_sunrise_4k.hdr", (0.7422, 0.0726, 0.6662), (15.503, 13.279, 6.120), 4.935, 4.0f, 0.7381))

  for k <- kiaras do
    s"The sun of ${k.file}" should "match the values of the reference script to 3 %" in:
      val path = kiaraDirectories.map(_.resolve(k.file)).find(Files.exists(_))
      assume(path.isDefined, s"${k.file} not found (set MENGER_HDRI_DIR to the directory of the Kiara maps)")
      val sun = path.map(EnvMapSun.measure).getOrElse(fail("unreachable"))
      info(f"direction ${asDirection(sun)}, half-angle ${sun.halfAngleDegrees}, E ${sun.irradiance}, I ${sun.intensity}%.3f, power share ${sun.powerShare}%.4f")
      angleBetween(asDirection(sun), normalised(k.direction)) should be < math.toRadians(0.1)
      sun.halfAngleDegrees shouldBe k.halfAngle
      sun.intensity.toDouble shouldBe k.intensity +- (0.03 * k.intensity)
      sun.irradiance(0).toDouble shouldBe k.irradiance._1 +- (0.03 * k.irradiance._1)
      sun.irradiance(1).toDouble shouldBe k.irradiance._2 +- (0.03 * k.irradiance._2)
      sun.irradiance(2).toDouble shouldBe k.irradiance._3 +- (0.03 * k.irradiance._3)
      sun.powerShare.toDouble shouldBe k.powerShare +- (0.03 * k.powerShare)
