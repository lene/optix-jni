package io.github.lene.optix

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices
import org.scalatest.flatspec.AnyFlatSpec

class ArchUnitSpec extends AnyFlatSpec {

  private val classes = new ClassFileImporter()
    .importPackages("io.github.lene.optix")

  "Package structure" should "be free of circular dependencies" in {
    slices()
      .matching("io.github.lene.optix.(*)..")
      .should()
      .allowEmptyShould(true)
      .beFreeOfCycles()
      .check(classes)
  }
}
