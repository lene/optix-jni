package menger.optix

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class RenderConfigSuite extends AnyFlatSpec with Matchers:

  "RenderConfig" should "have sensible defaults" in:
    val config = RenderConfig()
    config.shadows shouldBe false
    config.antialiasing shouldBe false
    config.aaMaxDepth shouldBe 2
    config.aaThreshold shouldBe 0.1f

  it should "create Default preset correctly" in:
    RenderConfig.Default.shadows shouldBe false
    RenderConfig.Default.antialiasing shouldBe false

  it should "create HighQuality preset correctly" in:
    RenderConfig.HighQuality.shadows shouldBe true
    RenderConfig.HighQuality.antialiasing shouldBe true
    RenderConfig.HighQuality.aaMaxDepth shouldBe 3
    RenderConfig.HighQuality.aaThreshold shouldBe 0.05f

  it should "accept valid aaMaxDepth values" in:
    noException shouldBe thrownBy { RenderConfig(aaMaxDepth = 1) }
    noException shouldBe thrownBy { RenderConfig(aaMaxDepth = 2) }
    noException shouldBe thrownBy { RenderConfig(aaMaxDepth = 3) }
    noException shouldBe thrownBy { RenderConfig(aaMaxDepth = 4) }

  it should "reject invalid aaMaxDepth values" in:
    an[IllegalArgumentException] shouldBe thrownBy { RenderConfig(aaMaxDepth = 0) }
    an[IllegalArgumentException] shouldBe thrownBy { RenderConfig(aaMaxDepth = 5) }

  it should "accept valid aaThreshold values" in:
    noException shouldBe thrownBy { RenderConfig(aaThreshold = 0.0f) }
    noException shouldBe thrownBy { RenderConfig(aaThreshold = 0.5f) }
    noException shouldBe thrownBy { RenderConfig(aaThreshold = 1.0f) }

  it should "reject invalid aaThreshold values" in:
    an[IllegalArgumentException] shouldBe thrownBy { RenderConfig(aaThreshold = -0.1f) }
    an[IllegalArgumentException] shouldBe thrownBy { RenderConfig(aaThreshold = 1.1f) }

  "CausticsConfig" should "have sensible defaults" in:
    val config = CausticsConfig()
    config.enabled shouldBe false
    config.photonsPerIteration shouldBe 100000
    config.iterations shouldBe 10
    config.initialRadius shouldBe 0.1f
    config.alpha shouldBe 0.7f

  it should "create Disabled preset correctly" in:
    CausticsConfig.Disabled.enabled shouldBe false

  it should "create Default preset correctly" in:
    CausticsConfig.Default.enabled shouldBe true
    CausticsConfig.Default.photonsPerIteration shouldBe 100000

  it should "create HighQuality preset correctly" in:
    CausticsConfig.HighQuality.enabled shouldBe true
    CausticsConfig.HighQuality.photonsPerIteration shouldBe 500000
    CausticsConfig.HighQuality.iterations shouldBe 20
    CausticsConfig.HighQuality.alpha shouldBe 0.8f

  it should "accept valid photonsPerIteration values" in:
    noException shouldBe thrownBy { CausticsConfig(photonsPerIteration = 1) }
    noException shouldBe thrownBy { CausticsConfig(photonsPerIteration = 10000000) }

  it should "reject invalid photonsPerIteration values" in:
    an[IllegalArgumentException] shouldBe thrownBy { CausticsConfig(photonsPerIteration = 0) }
    an[IllegalArgumentException] shouldBe thrownBy { CausticsConfig(photonsPerIteration = 10000001) }

  it should "accept valid iterations values" in:
    noException shouldBe thrownBy { CausticsConfig(iterations = 1) }
    noException shouldBe thrownBy { CausticsConfig(iterations = 1000) }

  it should "reject invalid iterations values" in:
    an[IllegalArgumentException] shouldBe thrownBy { CausticsConfig(iterations = 0) }
    an[IllegalArgumentException] shouldBe thrownBy { CausticsConfig(iterations = 1001) }

  it should "accept valid initialRadius values" in:
    noException shouldBe thrownBy { CausticsConfig(initialRadius = 0.001f) }
    noException shouldBe thrownBy { CausticsConfig(initialRadius = 10.0f) }

  it should "reject invalid initialRadius values" in:
    an[IllegalArgumentException] shouldBe thrownBy { CausticsConfig(initialRadius = 0.0f) }
    an[IllegalArgumentException] shouldBe thrownBy { CausticsConfig(initialRadius = -0.1f) }
    an[IllegalArgumentException] shouldBe thrownBy { CausticsConfig(initialRadius = 10.1f) }

  it should "accept valid alpha values" in:
    noException shouldBe thrownBy { CausticsConfig(alpha = 0.01f) }
    noException shouldBe thrownBy { CausticsConfig(alpha = 0.99f) }

  it should "reject invalid alpha values" in:
    an[IllegalArgumentException] shouldBe thrownBy { CausticsConfig(alpha = 0.0f) }
    an[IllegalArgumentException] shouldBe thrownBy { CausticsConfig(alpha = 1.0f) }
