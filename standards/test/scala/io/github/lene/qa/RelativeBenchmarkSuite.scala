package io.github.lene.qa

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

// Vendored from menger-toplevel shared/standards/test-scala/ -- edit the canonical copy, then
// `./bootstrap.sh sync`. Pure statistics only: no timing, so it runs in the regular test suite.

class RelativeBenchmarkSuite extends AnyFlatSpec with Matchers:

  private val Confidence = 0.95
  private val Limit = 2.0
  private val MaxSpread = 2.0

  private def measured(values: Double*): Measurement =
    RelativeBenchmark.measure(values.toVector, Limit, Confidence)

  private def ascending(n: Int): Vector[Double] = (1 to n).map(_.toDouble).toVector

  "medianInterval" should "use order statistics (x4, x12) for 15 rounds at 95%" in:
    RelativeBenchmark.medianInterval(ascending(15), Confidence) shouldBe ((4.0, 12.0))

  it should "use order statistics (x2, x8) for 9 rounds at 95%" in:
    RelativeBenchmark.medianInterval(ascending(9), Confidence) shouldBe ((2.0, 8.0))

  it should "use the full range (x1, x7) for 7 rounds at 95%" in:
    RelativeBenchmark.medianInterval(ascending(7), Confidence) shouldBe ((1.0, 7.0))

  "measure" should "compute the median of unsorted ratios" in:
    measured(3, 1, 2, 5, 4, 9, 7, 8, 6).median shouldBe 5.0

  "judge" should "pass when the whole interval is within the limit" in:
    val m = measured(1.0, 1.1, 1.2, 1.0, 1.1, 1.2, 1.0, 1.1, 1.2)
    RelativeBenchmark.judge(m, MaxSpread) shouldBe Verdict.Pass(m)

  it should "fail when the whole interval is beyond the limit" in:
    val m = measured(3.0, 3.1, 3.2, 3.0, 3.1, 3.2, 3.0, 3.1, 3.2)
    RelativeBenchmark.judge(m, MaxSpread) shouldBe Verdict.Fail(m)

  it should "be inconclusive when the interval straddles the limit" in:
    val m = measured(1.8, 1.9, 2.1, 1.8, 1.9, 2.1, 1.8, 1.9, 2.1)
    RelativeBenchmark.judge(m, MaxSpread) shouldBe a[Verdict.Inconclusive]

  it should "be inconclusive when the interval is too wide, even if beyond the limit" in:
    val m = measured(3.0, 3.5, 7.0, 3.0, 3.5, 7.0, 3.0, 3.5, 7.0)
    RelativeBenchmark.judge(m, MaxSpread) shouldBe a[Verdict.Inconclusive]

  "BenchConfig" should "reject fewer rounds than a 95% interval needs" in:
    an[IllegalArgumentException] should be thrownBy BenchConfig(rounds = BenchConfig.MinRounds - 1)

  "compare" should "not fail a subject that does the same work as the reference" in:
    val workSize = 20_000
    def work(): Unit =
      val _ = (1 to workSize).map(i => math.sqrt(i.toDouble)).sum
    val verdict = RelativeBenchmark.compare(
      Side("reference", () => work()),
      Side("subject", () => work()),
      maxRatio = Limit
    )
    verdict should not be a[Verdict.Fail]
