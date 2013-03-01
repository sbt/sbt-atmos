package com.typesafe.sbt

import sbt._
import sbt.Keys._
import sbt.Project.Initialize

// TODO: sigar libs?

object SbtAtmos extends Plugin {

  case class AtmosConfig(consoleClasspath: Classpath, traceClasspath: Classpath, aspectjWeaver: Option[File])

  val Atmos = config("atmos") hide
  val AtmosTrace = config("atmos-trace") hide
  val AtmosWeave = config("atmos-weave") hide

  object AtmosKeys {
    val atmosVersion = SettingKey[String]("atmos-version")
    val aspectjVersion = SettingKey[String]("aspectj-version")

    val atmosConfig = TaskKey[AtmosConfig]("atmos-config")
    val consoleClasspath = TaskKey[Classpath]("console-classpath")
    val traceClasspath = TaskKey[Classpath]("trace-classpath")
    val aspectjWeaver = TaskKey[Option[File]]("aspectj-weaver")

    val runWithConsole = InputKey[Unit]("run-with-console")
  }

  import AtmosKeys._

  lazy val atmosSettings: Seq[Setting[_]] = baseAtmosSettings

  def baseAtmosSettings: Seq[Setting[_]] = Seq(
    atmosVersion in Atmos := "1.2.0-SNAPSHOT",
    aspectjVersion in Atmos := "1.7.1",
    ivyConfigurations ++= Seq(Atmos, AtmosTrace, AtmosWeave),
    libraryDependencies <++= (atmosVersion in Atmos)(consoleDependencies),
    libraryDependencies <++= (atmosVersion in Atmos)(traceDependencies),
    libraryDependencies <++= (aspectjVersion in Atmos)(weaveDependencies),
    consoleClasspath in Atmos <<= (classpathTypes, update) map managedClasspath(Atmos),
    traceClasspath in Atmos <<= (classpathTypes, update) map managedClasspath(AtmosTrace),
    aspectjWeaver in Atmos <<= update map { report => report.matching(moduleFilter(organization = "org.aspectj", name = "aspectjweaver")) headOption },
    atmosConfig in Atmos <<= (consoleClasspath in Atmos, traceClasspath in Atmos, aspectjWeaver in Atmos) map AtmosConfig,
    inScope(Scope(This, Select(Compile), Select(run.key), This))(Seq(runner in runWithConsole in Compile <<= atmosRunner)).head,
    runWithConsole in Compile <<= Defaults.runTask(fullClasspath in Runtime, mainClass in run in Compile, runner in runWithConsole in Compile),

    // hacks to retain scala 2.9.2 jars in console dependencies
    ivyScala <<= ivyScala { is => is.map(_.copy(overrideScalaVersion = false)) },
    scalaVersion in Atmos := "2.9.2",
    scalaInstance in Atmos <<= atmosScalaInstance,
    update <<= transformUpdate
  )

  def consoleDependencies(version: String) = Seq(
    "com.typesafe.console" % "typesafe-console" % version % Atmos.name,
    "com.typesafe.atmos" % "atmos-query" % version % Atmos.name
  )

  // TODO: choose trace dependencies based on library dependencies and versions
  //       and if trace dependencies are not already specified
  def traceDependencies(version: String) = Seq(
    "com.typesafe.atmos" % "trace-akka-2.1.0" % version % AtmosTrace.name
  )

  def weaveDependencies(version: String) = Seq(
    "org.aspectj" % "aspectjweaver" % version % AtmosWeave.name
  )

  def managedClasspath(config: Configuration)(types: Set[String], update: UpdateReport): Classpath =
    Classpaths.managedJars(config, types, update)

  def atmosRunner: Initialize[Task[ScalaRun]] =
    (scalaInstance, baseDirectory, javaOptions, outputStrategy, javaHome, connectInput, atmosConfig in Atmos) map {
      (si, base, options, strategy, javaHomeDir, connectIn, config) =>
        new AtmosRun(ForkOptions(javaHomeDir, strategy, si.jars, Some(base), options, connectIn), config)
    }

  // TODO: start and stop atmos
  // TODO: write atmos configuration file for app
  class AtmosRun(forkConfig: ForkScalaRun, atmosConfig: AtmosConfig) extends ScalaRun {
    val forkRun = new ForkRun(forkConfig)

    def run(mainClass: String, classpath: Seq[File], options: Seq[String], log: Logger): Option[String] = {
      log.info("Starting Atmos and Typesafe Console")
      val atmosOptions = Seq("-classpath", Path.makeString(atmosConfig.consoleClasspath.files), "com.typesafe.atmos.AtmosMain", "collect", "analyze", "query")
      val strategy = forkConfig.outputStrategy getOrElse LoggedOutput(log)
      val atmosProcess = Fork.java.fork(None, atmosOptions, None, Map.empty[String, String], false, strategy)
      try {
        val cp = classpath ++ atmosConfig.traceClasspath.files
        val javaAgentOpts = atmosConfig.aspectjWeaver.toSeq map { w => "-javaagent:" + w.getAbsolutePath }
        val opts = javaAgentOpts ++ options
        forkRun.run(mainClass, cp, options, log)
      } finally {
        log.info("Stopping Atmos and Typesafe Console")
        //atmosProcess.destroy()
      }
    }
  }

  // Hacks for keeping scala 2.9.2 jars in atmos-console configuration

  def atmosScalaInstance: Initialize[Task[ScalaInstance]] =
    (appConfiguration, scalaOrganization, scalaVersion in Atmos) map {
      (app, org, version) => ScalaInstance(org, version, app.provider.scalaProvider.launcher)
    }

  def transformUpdate: Initialize[Task[UpdateReport]] =
    (update, scalaInstance in Atmos) map { (report, si) => resubstituteScalaJars(report, si) }

  def resubstituteScalaJars(report: UpdateReport, scalaInstance: ScalaInstance): UpdateReport = {
    import ScalaArtifacts._
    report.substitute { (configuration, module, artifacts) =>
      (configuration, module.organization, module.name) match {
        case (Atmos.name, Organization, LibraryID)  => (Artifact(LibraryID), scalaInstance.libraryJar) :: Nil
        case (Atmos.name, Organization, CompilerID) => (Artifact(CompilerID), scalaInstance.compilerJar) :: Nil
        case _ => artifacts
      }
    }
  }
}
