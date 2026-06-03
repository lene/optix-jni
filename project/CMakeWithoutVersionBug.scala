package com.github.sbt.jni.build

import sbt._
import sys.process._

class CMakeWithoutVersionBug(protected val configuration: Seq[String])
    extends BuildTool with ConfigureMakeInstall {

  override val name = "CMake"

  override def detect(baseDirectory: File) = baseDirectory.list().contains("CMakeLists.txt")

  override protected def templateMappings = Seq(
    "/com/github/sbt/jni/templates/CMakeLists.txt" -> "CMakeLists.txt"
  )

  override def getInstance(
      baseDir: File,
      buildDir: File,
      logger: Logger,
      isMultipleOutputs: Boolean
  ) = new Instance {

    override def log = logger
    override def baseDirectory = baseDir
    override def buildDirectory = buildDir
    override def multipleOutputs: Boolean = isMultipleOutputs

    def cmakeProcess(args: String*): ProcessBuilder = Process("cmake" +: args, buildDirectory)

    lazy val cmakeVersion =
      cmakeProcess("--version").lineStream.head
        .split("\\s+")
        .last
        .split("\\.") match {
        case Array(maj, min, rev) =>
          logger.info(s"Using CMake version $maj.$min.$rev")
          maj.toInt * 100 + min.toInt
        case _ => -1
      }

    def parallelOptions: Seq[String] =
      if (cmakeVersion >= 312) Seq("--parallel", parallelJobs.toString)
      else Seq.empty

    override def configure(target: File) = {
      // sbt-jni passes the CMake version as an extra path argument here.
      cmakeProcess(
        (s"-DCMAKE_INSTALL_PREFIX:PATH=${target.getAbsolutePath}" +: configuration) ++ Seq(
          baseDirectory.getAbsolutePath
        ): _*
      )
    }

    override def clean(): Unit = cmakeProcess(
      "--build",
      buildDirectory.getAbsolutePath,
      "--target",
      "clean"
    ).run(log)

    override def make(): ProcessBuilder = cmakeProcess(
      Seq("--build", buildDirectory.getAbsolutePath) ++ parallelOptions: _*
    )

    override def install(): ProcessBuilder =
      if (cmakeVersion >= 315) cmakeProcess("--install", buildDirectory.getAbsolutePath)
      else Process("make install", buildDirectory)
  }
}

object CMakeWithoutVersionBug {
  val DEFAULT_CONFIGURATION = Seq("-DCMAKE_BUILD_TYPE=Release", "-DSBT:BOOLEAN=true")

  def make(configuration: Seq[String] = DEFAULT_CONFIGURATION): BuildTool =
    new CMakeWithoutVersionBug(configuration)

  lazy val release: BuildTool = make()
}
