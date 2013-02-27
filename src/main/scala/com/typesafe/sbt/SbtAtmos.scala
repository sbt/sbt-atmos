package com.typesafe.sbt

import sbt._
import sbt.Keys._
import sbt.Project.Initialize

object SbtAtmos extends Plugin {

  val Atmos = config("atmos") hide
  val AtmosConsole = config("atmos-console") hide
  val AtmosTrace = config("atmos-trace") hide
  val AtmosWeave = config("atmos-weave") hide

  object AtmosKeys {
    val atmosVersion = SettingKey[String]("atmos-version")
    val aspectjVersion = SettingKey[String]("aspectj-version")

    val consoleClasspath = TaskKey[Classpath]("console-classpath")
    val traceClasspath = TaskKey[Classpath]("trace-classpath")
    val weaveClasspath = TaskKey[Classpath]("weave-classpath")

    val runWithConsole = InputKey[Unit]("run-with-console")
  }

  import AtmosKeys._

  lazy val atmosSettings: Seq[Setting[_]] = baseAtmosSettings

  def baseAtmosSettings: Seq[Setting[_]] = Seq(
    atmosVersion in Atmos := "1.2.0-SNAPSHOT",
    aspectjVersion in Atmos := "1.7.1",
    ivyConfigurations ++= Seq(AtmosConsole, AtmosTrace, AtmosWeave),
    libraryDependencies <++= (atmosVersion in Atmos)(consoleDependencies),
    libraryDependencies <++= (atmosVersion in Atmos)(traceDependencies),
    libraryDependencies <++= (aspectjVersion in Atmos)(weaveDependencies),
    consoleClasspath in Atmos <<= (classpathTypes, update) map managedClasspath(AtmosConsole),
    traceClasspath in Atmos <<= (classpathTypes, update) map managedClasspath(AtmosTrace),
    weaveClasspath in Atmos <<= (classpathTypes, update) map managedClasspath(AtmosWeave),
    inScope(Scope(This, Select(Compile), Select(run.key), This))(Seq(runner in runWithConsole in Compile <<= atmosRunnerInit)).head,
    runWithConsole in Compile <<= Defaults.runTask(fullClasspath in Runtime, mainClass in run in Compile, runner in runWithConsole in Compile)
  )

  def consoleDependencies(version: String) = Seq(
    "com.typesafe.console" % "typesafe-console" % version % AtmosConsole.name,
    "com.typesafe.atmos" % "atmos-query" % version % AtmosConsole.name
  )

  // TODO: choose trace dependencies based on library dependencies and versions
  def traceDependencies(version: String) = Seq(
    "com.typesafe.atmos" % "trace-akka-2.1.0" % version % AtmosTrace.name
  )

  def weaveDependencies(version: String) = Seq(
    "org.aspectj" % "aspectjweaver" % version % AtmosWeave.name
  )

  def managedClasspath(config: Configuration)(types: Set[String], update: UpdateReport): Classpath =
    Classpaths.managedJars(config, types, update)

  def atmosRunnerInit: Initialize[Task[ScalaRun]] =
    (scalaInstance, baseDirectory, javaOptions, outputStrategy, javaHome, connectInput) map {
      (si, base, options, strategy, javaHomeDir, connectIn) =>
        new AtmosRun(ForkOptions(javaHomeDir, strategy, si.jars, Some(base), options, connectIn))
    }
}

// TODO: start and stop atmos
// TODO: write atmos configuration file for app
class AtmosRun(config: ForkScalaRun) extends ScalaRun {
  val forkRun = new ForkRun(config)
  def run(mainClass: String, classpath: Seq[File], options: Seq[String], log: Logger): Option[String] = {
    log.info("Starting Atmos")
    try {
      forkRun.run(mainClass, classpath, options, log)
    } finally {
      log.info("Stopping Atmos")
    }
  }
}
