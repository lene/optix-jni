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

  // Same rule as menger's ArchitectureSpec "optix-jni public API": Java callers see every
  // JVM-public method, including Scala `private[optix]` ones. 0.4.4 shipped one exposing
  // scala.Option that only menger's gate caught (#59).
  "Public API" should "not expose Scala-specific types in method signatures" in:
    import com.tngtech.archunit.core.domain.JavaMethod
    import com.tngtech.archunit.core.importer.ImportOption
    import com.tngtech.archunit.lang.ArchCondition
    import com.tngtech.archunit.lang.ConditionEvents
    import com.tngtech.archunit.lang.SimpleConditionEvent
    import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods
    import scala.jdk.CollectionConverters._

    val productionClasses = new ClassFileImporter()
      .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
      .importPackages("io.github.lene.optix")
    val scalaSpecificPrefixes = Set(
      "scala.Option", "scala.collection", "scala.util.Try", "scala.util.Either", "scala.Function"
    )
    // Compiler-generated case-class / PartialFunction members and synthetic ($) methods.
    val scalaGeneratedMethodNames = Set(
      "productElement", "productIterator", "productElementNames", "productArity", "canEqual",
      "copy", "apply", "unapply", "applyOrElse", "isDefinedAt", "orElse"
    )
    val noScalaTypesInSignature: ArchCondition[JavaMethod] =
      new ArchCondition[JavaMethod]("not expose Scala-specific types in signatures"):
        override def check(method: JavaMethod, events: ConditionEvents): Unit =
          val generated =
            scalaGeneratedMethodNames.contains(method.getName) || method.getName.contains("$")
          val types = method.getRawParameterTypes.asScala.toList :+ method.getRawReturnType
          if !generated then
            types.filter(t => scalaSpecificPrefixes.exists(t.getFullName.startsWith)).foreach: t =>
              events.add(SimpleConditionEvent.violated(method,
                s"Public method '${method.getFullName}' uses Scala-specific type '${t.getFullName}'"))

    methods().that()
      .areDeclaredInClassesThat().resideInAPackage("io.github.lene.optix..")
      .and().areDeclaredInClassesThat().haveSimpleNameNotEndingWith("$")
      .and().arePublic()
      .should(noScalaTypesInSignature)
      .check(productionClasses)
