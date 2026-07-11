import com.github.sbt.jni.build.CMakeWithoutVersionBug

name := "optix-jni"
version := "0.1.17"
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

publishTo := sonatypePublishToBundle.value

sonatypeCredentialHost := "central.sonatype.com"
publishMavenStyle := true
crossPaths := false
pgpPassphrase := sys.env.get("PGP_PASSPHRASE").map(_.toArray)

scalacOptions ++= Seq("-deprecation", "-explain", "-feature", "-Wunused:imports")

resolvers += Resolver.file("local-ivy", file(Path.userHome + "/.ivy2/local"))(Resolver.ivyStylePatterns)

Compile / semanticdbEnabled := true

val relativeCompilerPluginPrefix = "-Xplugin:target/compiler_plugins/"

def absolutizeCompilerPluginPath(projectBase: File)(option: String): String =
  if (option.startsWith(relativeCompilerPluginPrefix)) {
    val relativePluginPath = option.stripPrefix("-Xplugin:")
    s"-Xplugin:${(projectBase / relativePluginPath).getAbsolutePath}"
  } else {
    option
  }

Compile / wartremoverErrors ++= Seq(
  Wart.Var,
  Wart.While,
  Wart.AsInstanceOf,
  Wart.IsInstanceOf,
  Wart.Throw
)

Compile / scalacOptions := (Compile / scalacOptions).value.map(
  absolutizeCompilerPluginPath(baseDirectory.value)
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

  // Trigger the native build first so the PTX file exists.
  // When CUDA is absent, CMakeLists.txt builds a stub .so and returns early,
  // so nativeCompile succeeds with no PTX output.
  nativeCompile.value
  val ptxResources = if (ptxSource.exists()) {
    val ptxResource = (Compile / resourceManaged).value / "native" / platform / "optix_shaders.ptx"
    IO.copyFile(ptxSource, ptxResource)
    log.debug(s"Bundled PTX into managed resources: $ptxResource")
    Seq(ptxResource)
  } else {
    log.warn(s"No PTX at $ptxSource — stub build or CUDA unavailable. PTX will not be bundled.")
    Seq.empty
  }

  ptxResources ++ nativeApiResources
}.taskValue

// Safety gate: abort packaging if PTX is absent (indicates stub build).
// Fires during `sbt package` / `sbt publish` but NOT during `sbt test` or `sbt testOnly`.
Compile / packageBin := {
  val ptxSource = target.value / "native" / "x86_64-linux" / "bin" / "optix_shaders.ptx"
  if (!ptxSource.exists())
    sys.error(s"Stub build detected: PTX not found at $ptxSource. CUDA/OptiX required. Ensure CUDA_HOME is set and nvcc is on PATH.")
  (Compile / packageBin).value
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
  "io.github.lene" %% "menger-common" % "0.1.4",
  "com.typesafe.scala-logging" %% "scala-logging" % "3.9.6",
  "ch.qos.logback" % "logback-classic" % "1.5.32",
  "org.scalatest" %% "scalatest" % "3.2.20" % Test,
  "org.scalatestplus" %% "scalacheck-1-19" % "3.2.20.0" % Test,
  "com.tngtech.archunit" % "archunit" % "1.3.0" % Test
)

// MiMa binary compatibility — baseline against last published release.
// From 1.0 onward, MiMa failures block release (SemVer contract).
import com.typesafe.tools.mima.core._
enablePlugins(MimaPlugin)
mimaPreviousArtifacts := Set(
  "io.github.lene" % "optix-jni" % "0.1.5"
)
// Check both directions: forward (1.0 must be compatible with 0.1.5) and
// backward (0.1.5 users can upgrade to 1.0 without recompilation).
mimaBinaryIssueFilters ++= Seq(
  // Sprint 30.4: setDenoisingEnabled/isDenoisingEnabled changed from @native to
  // guarded Scala wrappers. Public method signatures unchanged — internal native
  // methods renamed to *Native suffix. Backward compatible at API level.
  ProblemFilters.exclude[DirectMissingMethodProblem]("io.github.lene.optix.OptiXRenderer.setDenoisingEnabled"),
  ProblemFilters.exclude[DirectMissingMethodProblem]("io.github.lene.optix.OptiXRenderer.isDenoisingEnabled"),
  ProblemFilters.exclude[DirectMissingMethodProblem]("io.github.lene.optix.OptiXRenderer.setAccumulationFramesNative"),
)
