package io.github.lene.qa

import scala.annotation.tailrec

import org.scalatest.Assertion
import org.scalatest.Assertions
import org.scalatest.Informing
import org.scalatest.Tag

// Vendored from menger-toplevel shared/standards/test-scala/ -- edit the canonical copy, then
// `./bootstrap.sh sync`. The same file is compiled by every repo that has performance gates.

/** Timing-based gates. Excluded from the regular test run and run alone, sequentially, by the
  * `perf` suite (PERF_ONLY=1), so other tests don't compete with the measurements. */
object Perf extends Tag("Perf")

/** One side of a comparison. `op` is the timed unit of work; `prepare` runs untimed before each
  * sample (e.g. to load a scene), followed by one untimed `op` that absorbs lazy rebuilds. */
final case class Side(name: String, op: () => Unit, prepare: () => Unit = () => ())

/** Per-round time ratios (subject / reference), their median, and a distribution-free
  * confidence interval for that median, judged against `limit`. */
final case class Measurement(
    ratios: Vector[Double],
    median: Double,
    low: Double,
    high: Double,
    limit: Double
):
  def spread: Double = high / low

  override def toString: String =
    f"median $median%.3f, CI [$low%.3f, $high%.3f], limit $limit%.3f, " +
      s"ratios ${ratios.map(r => f"$r%.3f").mkString(" ")}"

enum Verdict:
  case Pass(measurement: Measurement)
  case Fail(measurement: Measurement)
  case Inconclusive(measurement: Measurement, reason: String)

final case class BenchConfig(
    rounds: Int = BenchConfig.DefaultRounds,
    warmupRounds: Int = BenchConfig.DefaultWarmupRounds,
    minBatchNanos: Long = BenchConfig.DefaultMinBatchNanos,
    maxBatchReps: Int = BenchConfig.DefaultMaxBatchReps,
    confidence: Double = BenchConfig.DefaultConfidence,
    maxSpread: Double = BenchConfig.DefaultMaxSpread,
    collectGarbageBeforeSample: Boolean = false
):
  require(rounds >= BenchConfig.MinRounds, s"rounds must be >= ${BenchConfig.MinRounds}")
  require(confidence > 0.0 && confidence < 1.0, "confidence must be in (0, 1)")

object BenchConfig:
  val DefaultRounds = 15
  val DefaultWarmupRounds = 2
  // Batches shorter than this are dominated by timer resolution and scheduling jitter.
  val DefaultMinBatchNanos = 10_000_000L
  val DefaultMaxBatchReps = 1 << 16
  val DefaultConfidence = 0.95
  // A confidence interval wider than this factor means the environment changed during the
  // measurement (e.g. power throttling), which breaks the interval's assumptions.
  val DefaultMaxSpread = 2.0
  // Below 9 rounds no order-statistic interval reaches 95% coverage.
  val MinRounds = 9

  /** For CPU-bound work in the JVM, where garbage-collection pauses and JIT compilation
    * dominate short samples: collect garbage before each (untimed), warm up longer so the JIT
    * settles, and time longer batches so the remaining pauses average out. */
  val JvmCpu: BenchConfig = BenchConfig(
    warmupRounds = 5,
    minBatchNanos = 100_000_000L,
    collectGarbageBeforeSample = true
  )

/** Interleaved relative benchmark: the reference and the subject are measured back to back in
  * every round, in alternating order, so drift (throttling, background load) and order effects
  * cancel within each round instead of skewing a comparison between separate measurements. */
object RelativeBenchmark:

  /** Gate: subject time / reference time must not exceed `maxRatio`. */
  def compare(
      reference: Side,
      subject: Side,
      maxRatio: Double,
      config: BenchConfig = BenchConfig()
  ): Verdict =
    val referenceReps = batchReps(reference, config)
    val subjectReps = batchReps(subject, config)
    (1 to config.warmupRounds).foreach { _ =>
      sampleNanosPerOp(reference, referenceReps, config)
      sampleNanosPerOp(subject, subjectReps, config)
    }
    val ratios = (0 until config.rounds).map { round =>
      if round % 2 == 0 then
        val referenceTime = sampleNanosPerOp(reference, referenceReps, config)
        sampleNanosPerOp(subject, subjectReps, config) / referenceTime
      else
        val subjectTime = sampleNanosPerOp(subject, subjectReps, config)
        subjectTime / sampleNanosPerOp(reference, referenceReps, config)
    }.toVector
    judge(measure(ratios, maxRatio, config.confidence), config.maxSpread)

  def measure(ratios: Vector[Double], limit: Double, confidence: Double): Measurement =
    val sorted = ratios.sorted
    val (low, high) = medianInterval(sorted, confidence)
    Measurement(ratios, median(sorted), low, high, limit)

  /** PASS if the whole interval is within the limit, FAIL if the whole interval is beyond it,
    * INCONCLUSIVE if it straddles the limit or is too wide to trust. */
  def judge(measurement: Measurement, maxSpread: Double): Verdict =
    if measurement.spread > maxSpread then
      Verdict.Inconclusive(
        measurement,
        f"too noisy: interval spans ${measurement.spread}%.2fx (max $maxSpread%.2fx)"
      )
    else if measurement.high <= measurement.limit then Verdict.Pass(measurement)
    else if measurement.low > measurement.limit then Verdict.Fail(measurement)
    else Verdict.Inconclusive(measurement, "interval straddles the limit")

  /** Sign-test confidence interval for the median: the order statistics (x(k), x(n+1-k)) for
    * the largest k with 2 * P(Bin(n, 1/2) <= k - 1) <= 1 - confidence. Needs no assumption
    * about the ratios' distribution and is deterministic. */
  def medianInterval(sorted: Vector[Double], confidence: Double): (Double, Double) =
    val n = sorted.size
    val k = (1 to n / 2)
      .takeWhile(candidate => 2 * binomialHalfCdf(n, candidate - 1) <= 1 - confidence)
      .lastOption
      .getOrElse(1)
    (sorted(k - 1), sorted(n - k))

  private def median(sorted: Vector[Double]): Double =
    val n = sorted.size
    if n % 2 == 1 then sorted(n / 2) else (sorted(n / 2 - 1) + sorted(n / 2)) / 2

  private def binomialHalfCdf(n: Int, upTo: Int): Double =
    (0 to upTo).map(binomial(n, _)).sum / math.pow(2, n)

  private def binomial(n: Int, k: Int): Double =
    (1 to k).foldLeft(1.0)((product, i) => product * (n - k + i) / i)

  @tailrec
  private def batchReps(side: Side, config: BenchConfig, reps: Int = 1): Int =
    val batchNanos = sampleNanosPerOp(side, reps, config) * reps
    if batchNanos >= config.minBatchNanos || reps >= config.maxBatchReps then reps
    else batchReps(side, config, reps * 2)

  private def sampleNanosPerOp(side: Side, reps: Int, config: BenchConfig): Double =
    if config.collectGarbageBeforeSample then System.gc()
    side.prepare()
    side.op()
    val start = System.nanoTime()
    (1 to reps).foreach(_ => side.op())
    (System.nanoTime() - start).toDouble / reps

/** Turns a verdict into a test outcome: FAIL fails the test, INCONCLUSIVE cancels it (the
  * `perf` suite reports cancellations as a visible SKIP, never as a failure). */
trait PerfGate extends Assertions with Informing:
  def assertWithin(label: String, verdict: Verdict): Assertion =
    verdict match
      case Verdict.Pass(measurement) =>
        info(s"$label: $measurement")
        succeed
      case Verdict.Fail(measurement) =>
        fail(s"PERF-FAIL $label: $measurement")
      case Verdict.Inconclusive(measurement, reason) =>
        cancel(s"PERF-INCONCLUSIVE $label: $reason; $measurement")
