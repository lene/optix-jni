import com.github.sbt.jni.build.CMakeWithoutVersionBug
import xerial.sbt.Sonatype.sonatypeCentralHost

name := "optix-jni"
version := "0.1.0"
scalaVersion := "3.8.3"

enablePlugins(JniNative)

organization := "io.github.lene"
description := "JNI bindings for NVIDIA OptiX ray tracing"
homepage := Some(url("https://github.com/lene/optix-jni"))
licenses := Seq("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0"))
scmInfo := Some(ScmInfo(
  url("https://github.com/lene/optix-jni"),
  "scm:git:git@github.com:lene/optix-jni.git"
))
developers := List(
  Developer("lene", "Lene Preuss", "lene.preuss@gmail.com", url("https://github.com/lene"))
)

sonatypeCredentialHost := sonatypeCentralHost
publishTo := sonatypePublishToBundle.value
publishMavenStyle := true
crossPaths := false
pomIncludeRepository := { _ => false }

scalacOptions ++= Seq("-deprecation", "-explain", "-feature", "-Wunused:imports")
Compile / doc / scalacOptions := (Compile / scalacOptions).value.filterNot(
  _.startsWith("-Wunused")
)

Compile / semanticdbEnabled := true

coverageMinimumStmtTotal := 60
coverageFailOnMinimum := true
coverageHighlighting := true

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

  (Compile / compile).value
}

// Bundle the PTX shader into the JAR as a managed resource, alongside the .so.
// nativeCompile must run first (it produces the PTX); this mirrors how JniPackage
// bundles the .so via resourceGenerators so the ordering is guaranteed correct.
Compile / resourceGenerators += Def.task {
  val log = streams.value.log
  val platform = "x86_64-linux"
  val ptxSource = target.value / "native" / platform / "bin" / "optix_shaders.ptx"
  val nativeApiResourceRoot = (Compile / resourceManaged).value / "optix-jni-native"
  val nativeApiResources = Seq(
    sourceDirectory.value / "main" / "native" / "include" -> nativeApiResourceRoot / "include",
    sourceDirectory.value / "main" / "native" / "shaders" -> nativeApiResourceRoot / "shaders"
  ).flatMap { case (sourceRoot, targetRoot) =>
    (sourceRoot ** "*").get.filter(_.isFile).map { sourceFile =>
      val relativePath = sourceFile.relativeTo(sourceRoot).fold(sourceFile.getName)(_.getPath)
      val targetFile = targetRoot / relativePath
      IO.copyFile(sourceFile, targetFile)
      targetFile
    }
  }

  // Trigger the native build first so the PTX file exists
  nativeCompile.value
  val ptxResources = if (ptxSource.exists()) {
    val ptxResource = (Compile / resourceManaged).value / "native" / platform / "optix_shaders.ptx"
    IO.copyFile(ptxSource, ptxResource)
    log.debug(s"Bundled PTX into managed resources: $ptxResource")
    Seq(ptxResource)
  } else {
    log.warn(s"PTX file not found after nativeCompile: $ptxSource")
    Seq.empty
  }

  ptxResources ++ nativeApiResources
}.taskValue

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
  "io.github.lene" %% "menger-common" % "0.1.0",
  "com.typesafe.scala-logging" %% "scala-logging" % "3.9.6",
  "ch.qos.logback" % "logback-classic" % "1.5.32",
  "org.scalatest" %% "scalatest" % "3.2.20" % Test,
  "org.scalatestplus" %% "scalacheck-1-19" % "3.2.20.0" % Test
)
