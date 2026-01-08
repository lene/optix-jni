import com.github.sbt.jni.build.CMakeWithoutVersionBug

name := "optix-jni"
version := "0.4.1"
scalaVersion := "3.7.3"

organization := "io.github.lilacashes"
description := "JNI bindings for NVIDIA OptiX ray tracing"
homepage := Some(url("https://gitlab.com/lilacashes/menger"))
licenses := Seq("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0"))
scmInfo := Some(ScmInfo(
  url("https://gitlab.com/lilacashes/menger"),
  "scm:git:git@gitlab.com:lilacashes/menger.git"
))
developers := List(
  Developer("lene", "Lene Preuss", "lene.preuss@gmail.com", url("https://gitlab.com/lilacashes"))
)

Compile / semanticdbEnabled := true

Compile / wartremoverErrors ++= Seq(
  Wart.Var,
  Wart.While,
  Wart.AsInstanceOf,
  Wart.IsInstanceOf,
  Wart.Throw
)

// Configure native build
nativeCompile / sourceDirectory := sourceDirectory.value / "main" / "native"
nativeBuildTool := CMakeWithoutVersionBug.make(Seq(
  "-Wno-dev",
  "--log-level=WARNING"
))

// Auto-clean CMake cache if it's from a different build location (e.g., Docker)
Compile / compile := {
  val log = streams.value.log
  val cacheFile = target.value / "native" / "x86_64-linux" / "build" / "CMakeCache.txt"
  val nativeDir = target.value / "native"

  if (cacheFile.exists()) {
    val expectedPath = (nativeCompile / sourceDirectory).value.getAbsolutePath
    val cacheContent = IO.read(cacheFile)

    if (!cacheContent.contains(expectedPath)) {
      log.warn(s"CMake cache from different build location detected (likely Docker container)")
      log.warn(s"Cleaning native build directory: ${nativeDir.getAbsolutePath}")
      IO.delete(nativeDir)
      log.info("Native build directory cleaned. CMake will regenerate cache on next build.")
    }
  }

  // Clean stale PTX copies before compile
  val stalePtxLocations = Seq(
    (Compile / classDirectory).value / "native" / "x86_64-linux" / "sphere_combined.ptx",
    baseDirectory.value.getParentFile / "target" / "native" / "x86_64-linux" / "bin" / "sphere_combined.ptx"
  )
  stalePtxLocations.foreach { ptxFile =>
    if (ptxFile.exists()) {
      IO.delete(ptxFile)
      log.info(s"Cleaned stale PTX copy: $ptxFile")
    }
  }

  val compileResult = (Compile / compile).value

  // Copy PTX file to classes directory (sbt-jni only copies .so/.dll/.dylib)
  val ptxSource = target.value / "native" / "x86_64-linux" / "bin" / "sphere_combined.ptx"
  val ptxDest = (Compile / classDirectory).value / "native" / "x86_64-linux" / "sphere_combined.ptx"
  if (ptxSource.exists()) {
    IO.copyFile(ptxSource, ptxDest)
    log.debug(s"Copied PTX file: $ptxSource -> $ptxDest")
  }

  compileResult
}

// Native test task to run C++ Google Test suite
lazy val nativeTest = taskKey[Unit]("Run native C++ tests")
nativeTest := {
  val log = streams.value.log
  val buildDir = target.value / "native" / "x86_64-linux" / "build"
  val testExe = buildDir / "optixcontext_test"

  nativeCompile.value

  if (testExe.exists()) {
    log.info("Running C++ unit tests (Google Test)...")

    import scala.sys.process._
    val result = Process(
      Seq(testExe.getAbsolutePath),
      None,
      "LD_LIBRARY_PATH" -> "/usr/local/cuda/lib64"
    ).!

    if (result != 0) {
      throw new RuntimeException(s"Native tests failed with exit code $result")
    }
    log.info("C++ unit tests passed")
  } else {
    log.warn(s"C++ test executable not found at ${testExe.getAbsolutePath}")
    log.warn("Skipping native tests (BUILD_OPTIX_TESTS may be disabled)")
  }
}

// Make 'test' depend on 'nativeTest' so both Scala and C++ tests run
Test / test := {
  nativeTest.value
  (Test / test).value
}

// Set library path for tests to find native library
Test / javaOptions ++= Seq(
  s"-Djava.library.path=${(Test / classDirectory).value / "native" / "x86_64-linux"}:${(Compile / classDirectory).value / "native" / "x86_64-linux"}:${target.value / "native" / "x86_64-linux" / "bin"}",
  "-Dlogback.statusListenerClass=ch.qos.logback.core.status.NopStatusListener"
)
Test / fork := true

libraryDependencies ++= Seq(
  "com.typesafe.scala-logging" %% "scala-logging" % "3.9.6",
  "ch.qos.logback" % "logback-classic" % "1.5.19",
  "org.scalatest" %% "scalatest" % "3.2.19" % Test,
  "org.scalatestplus" %% "scalacheck-1-18" % "3.2.19.0" % Test
)
