package io.github.lene.optix

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class RenderStatsTest extends AnyFlatSpec with Matchers:

  "RayStats" should "compute msPerMray correctly for non-zero ray count" in {
    val stats = RayStats(
      totalRays = 2_000_000L,
      primaryRays = 1_000_000L,
      reflectedRays = 500_000L,
      refractedRays = 300_000L,
      shadowRays = 200_000L,
      aaRays = 0L,
      aaStackOverflows = 0L,
      maxDepthReached = 3,
      minDepthReached = 1,
      frameMs = 100.0f
    )
    stats.msPerMray.shouldBe(100.0f / 2.0f +- 0.001f)
  }

  it should "return 0 msPerMray when totalRays is 0" in {
    val stats = RayStats(
      totalRays = 0L,
      primaryRays = 0L,
      reflectedRays = 0L,
      refractedRays = 0L,
      shadowRays = 0L,
      aaRays = 0L,
      aaStackOverflows = 0L,
      maxDepthReached = 0,
      minDepthReached = 0,
      frameMs = 50.0f
    )
    stats.msPerMray.shouldBe(0.0f)
  }

  "RenderResult.stats" should "propagate frameMs to RayStats" in {
    val result = RenderResult(
      image = Array.empty[Byte],
      totalRays = 1_000_000L,
      primaryRays = 500_000L,
      reflectedRays = 0L,
      refractedRays = 0L,
      shadowRays = 0L,
      aaRays = 0L,
      aaStackOverflows = 0L,
      maxDepthReached = 1,
      minDepthReached = 1,
      frameMs = 42.0f
    )
    result.stats.frameMs.shouldBe(42.0f)
  }
