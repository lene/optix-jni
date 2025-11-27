package menger.optix

import org.scalatest.Tag

/** Shared test tags for test categorization and filtering.
  *
  * Tags can be used to exclude tests from certain runs (e.g., compute-sanitizer)
  * using ScalaTest's `-l` flag: `testOnly -- -l Slow`
  */
object Slow extends Tag("Slow")
