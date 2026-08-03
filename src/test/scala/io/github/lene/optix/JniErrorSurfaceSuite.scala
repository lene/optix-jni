package io.github.lene.optix

import java.nio.file.Files
import java.nio.file.Path

import scala.jdk.CollectionConverters.*

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Fitness function for the Sprint 35 F11/CR-9 + CR-4 native error surface.
  *
  * It source-scans `JNIBindings.cpp` (rather than exercising the JVM, which cannot observe a
  * missing `catch(...)` — that is undefined behaviour, not a catchable event) and asserts two
  * invariants that must never regress:
  *
  *   1. **CR-4** — every `JNIEXPORT` body has a trailing catch-all (`catch (...)` or one of the
  *      `JNI_CATCH_UNKNOWN_*` macros that expand to it). Without it a non-`std::exception` native
  *      throw unwinds across the JNI frame into the JVM as UB.
  *   2. **F11/CR-9** — no production throw routes through `java/lang/RuntimeException`; runtime
  *      failures use the optix-jni-owned [[OptiXException]] (`OPTIX_EXCEPTION_CLASS`).
  *      `java/lang/IllegalArgumentException` stays allowed: argument-precondition violations are
  *      deliberately not part of the OptiXException hierarchy.
  */
class JniErrorSurfaceSuite extends AnyFlatSpec with Matchers:

  private val source: String =
    val path = Path.of("src", "main", "native", "JNIBindings.cpp")
    Files.readString(path)

  /** One entry per `JNIEXPORT` function: its Scala-visible name and full body text. */
  private val jniExports: List[(String, String)] =
    val lines = source.split("\n", -1).toList
    val starts = lines.zipWithIndex.collect { case (l, i) if l.startsWith("JNIEXPORT") => i }
    val bounds = starts :+ lines.length
    bounds.sliding(2).toList.collect { case List(a, b) =>
      val body = lines.slice(a, b).mkString("\n")
      val name = "OptiXRenderer_(\\w+)".r.findFirstMatchIn(lines(a)).map(_.group(1)).getOrElse("?")
      name -> body
    }

  "Every JNIEXPORT" should "end with a catch-all so a non-std::exception throw cannot reach the JVM" in:
    jniExports should not be empty
    val missing = jniExports.collect {
      case (name, body) if !body.contains("catch (...)") && !body.contains("JNI_CATCH_UNKNOWN") =>
        name
    }
    withClue(s"JNIEXPORTs without a catch-all: ${missing.mkString(", ")} — "):
      missing shouldBe empty

  "Production native throws" should "not route through java/lang/RuntimeException" in:
    source should not include "java/lang/RuntimeException"

  they should "route runtime failures through the optix-jni-owned OptiXException class" in:
    source should include ("OPTIX_EXCEPTION_CLASS")
    source should include ("\"io/github/lene/optix/OptiXException\"")
