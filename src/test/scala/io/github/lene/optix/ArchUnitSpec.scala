package io.github.lene.optix

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices
import org.scalatest.flatspec.AnyFlatSpec

/** Module-scoped ArchUnit rules (T5, Sprint 32).
  *
  * Fitness functions:
  * - No circular dependencies in optix-jni package structure
  */
class ArchUnitSpec extends AnyFlatSpec:

  private val classes = new ClassFileImporter()
    .importPackages("io.github.lene.optix")

  "Package structure" should "be free of circular dependencies" in:
    slices()
      .matching("io.github.lene.optix.(*)..")
      .should()
      .beFreeOfCycles()
      .allowEmptyShould(true)
      .check(classes)
