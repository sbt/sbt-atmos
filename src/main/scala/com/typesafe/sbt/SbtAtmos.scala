/**
 *  Copyright (C) 2013 Typesafe, Inc <http://typesafe.com>
 */
package com.typesafe.sbt

import sbt._
import sbt.Keys._
import sbt.Project.Initialize

object SbtAtmos extends Plugin {

  case class AtmosInputs(atmosClasspath: Classpath, consoleClasspath: Classpath, traceClasspath: Classpath, aspectjWeaver: Option[File], atmosConfig: File, consoleConfig: File, traceConfig: File)

  val Atmos = config("atmos") hide
  val AtmosConsole = config("atmos-console") hide
  val AtmosTrace = config("atmos-trace") hide
  val AtmosWeave = config("atmos-weave") hide

  object AtmosKeys {
    val atmosVersion = SettingKey[String]("atmos-version")
    val aspectjVersion = SettingKey[String]("aspectj-version")

    val atmosClasspath = TaskKey[Classpath]("atmos-classpath")
    val consoleClasspath = TaskKey[Classpath]("console-classpath")
    val traceClasspath = TaskKey[Classpath]("trace-classpath")
    val aspectjWeaver = TaskKey[Option[File]]("aspectj-weaver")

    val atmosDirectory = SettingKey[File]("atmos-directory")
    val atmosConfigDirectory = SettingKey[File]("atmos-config-directory")
    val atmosConfigString = SettingKey[String]("atmos-config-string")
    val atmosConfig = TaskKey[File]("atmos-config")
    val consoleConfigString = SettingKey[String]("console-config-string")
    val consoleConfig = TaskKey[File]("console-config")
    val traceConfigString = SettingKey[String]("trace-config-string")
    val traceConfig = TaskKey[File]("trace-config")

    val atmosInputs = TaskKey[AtmosInputs]("atmos-inputs")

    val runWithConsole = InputKey[Unit]("run-with-console")
  }

  import AtmosKeys._

  lazy val atmosSettings: Seq[Setting[_]] = inConfig(Atmos)(atmosScopedSettings) ++ atmosUnscopedSettings

  def atmosScopedSettings: Seq[Setting[_]] = Seq(
    atmosVersion := "1.2.0-SNAPSHOT",
    aspectjVersion := "1.7.2",

    atmosClasspath <<= managedClasspath(Atmos),
    consoleClasspath <<= managedClasspath(AtmosConsole),
    traceClasspath <<= managedClasspath(AtmosTrace),
    aspectjWeaver <<= findAspectjWeaver,

    atmosDirectory <<= target / "atmos",
    atmosConfigDirectory <<= (atmosDirectory) / "conf",
    atmosConfigString := defaultAtmosConfig,
    atmosConfig <<= writeConfig(atmosConfigString, "atmos"),
    traceConfigString <<= name apply defaultTraceConfig,
    traceConfig <<= writeConfig(traceConfigString, "trace"),
    consoleConfigString <<= name apply defaultConsoleConfig,
    consoleConfig <<= writeConfig(consoleConfigString, "console"),

    atmosInputs <<= (atmosClasspath, consoleClasspath, traceClasspath, aspectjWeaver, atmosConfig, consoleConfig, traceConfig) map AtmosInputs,

    // hacks to retain scala 2.9.2 jars in console dependencies
    scalaVersion := "2.9.2",
    scalaInstance <<= atmosScalaInstance
  )

  def atmosUnscopedSettings: Seq[Setting[_]] = Seq(
    ivyConfigurations ++= Seq(Atmos, AtmosConsole, AtmosTrace, AtmosWeave),

    libraryDependencies <++= (atmosVersion in Atmos)(atmosDependencies),
    libraryDependencies <++= (atmosVersion in Atmos)(consoleDependencies),
    libraryDependencies <++= (libraryDependencies, atmosVersion in Atmos)(traceDependencies),
    libraryDependencies <++= (aspectjVersion in Atmos)(weaveDependencies),

    inScope(Scope(This, Select(Compile), Select(run.key), This))(Seq(runner in runWithConsole in Compile <<= atmosRunner)).head,
    runWithConsole in Compile <<= Defaults.runTask(fullClasspath in Runtime, mainClass in run in Compile, runner in runWithConsole in Compile),

    // hacks to retain scala 2.9.2 jars in console dependencies
    ivyScala <<= ivyScala { is => is.map(_.copy(overrideScalaVersion = false)) },
    update <<= transformUpdate
  )

  def atmosDependencies(version: String) = Seq(
    "com.typesafe.atmos" % "atmos-query" % version % Atmos.name
  )

  def consoleDependencies(version: String) = Seq(
    "com.typesafe.console" % "typesafe-console" % version % AtmosConsole.name
  )

  def traceDependencies(dependencies: Seq[ModuleID], version: String) = {
    if (containsTrace(dependencies)) Seq.empty[ModuleID]
    else if (containsAkka21(dependencies)) Seq("com.typesafe.atmos" % "trace-akka-2.1.1" % version % AtmosTrace.name)
    else if (containsAkka20(dependencies)) Seq("com.typesafe.atmos" % "trace-akka-2.0.5" % version % AtmosTrace.name)
    else Seq.empty[ModuleID]
  }

  def containsTrace(dependencies: Seq[ModuleID]): Boolean = dependencies exists { module =>
    module.organization == "com.typesafe.atmos" && module.name.startsWith("trace-akka")
  }

  def containsAkka20(dependencies: Seq[ModuleID]): Boolean = dependencies exists { module =>
    module.organization == "com.typesafe.akka" && module.name.startsWith("akka-") && module.revision.startsWith("2.0.")
  }

  def containsAkka21(dependencies: Seq[ModuleID]): Boolean = dependencies exists { module =>
    module.organization == "com.typesafe.akka" && module.name.startsWith("akka-") && module.revision.startsWith("2.1.")
  }

  def weaveDependencies(version: String) = Seq(
    "org.aspectj" % "aspectjweaver" % version % AtmosWeave.name
  )

  def managedClasspath(config: Configuration): Initialize[Task[Classpath]] =
    (classpathTypes, update) map { (types, report) => Classpaths.managedJars(config, types, report) }

  def findAspectjWeaver: Initialize[Task[Option[File]]] =
    update map { report => report.matching(moduleFilter(organization = "org.aspectj", name = "aspectjweaver")) headOption }

  def defaultTraceConfig(name: String) = """
    |atmos {
    |  trace {
    |    enabled = true
    |    node = "%s"
    |  }
    |}
  """.trim.stripMargin.format(name)

  def defaultAtmosConfig = """
    |atmos {
    |  mode = local
    |  trace {
    |    event-handlers = ["com.typesafe.atmos.trace.store.MemoryTraceEventListener", "com.typesafe.atmos.analytics.analyze.LocalAnalyzerTraceEventListener"]
    |  }
    |}
  """.trim.stripMargin

  def defaultConsoleConfig(name: String) = """
    |app.name = "%s"
  """.trim.stripMargin.format(name)

  def writeConfig(configKey: SettingKey[String], name: String): Initialize[Task[File]] =
    (atmosConfigDirectory in Atmos, configKey) map {
      (confDir, conf) =>
        val dir = confDir / name
        val file = dir / "application.conf"
        IO.write(file, conf)
        dir
    }

  def atmosRunner: Initialize[Task[ScalaRun]] =
    (scalaInstance, baseDirectory, javaOptions, outputStrategy, javaHome, connectInput, atmosInputs in Atmos) map {
      (si, base, options, strategy, javaHomeDir, connectIn, inputs) =>
        val javaAgent = inputs.aspectjWeaver.toSeq map { w => "-javaagent:" + w.getAbsolutePath }
        val aspectjOptions = Seq("-Dorg.aspectj.tracing.factory=default")
        val atmosOptions = javaAgent ++ aspectjOptions
        val forkConfig = ForkOptions(javaHomeDir, strategy, si.jars, Some(base), options ++ atmosOptions, connectIn)
        new AtmosRun(forkConfig, inputs)
    }

  class AtmosRun(forkConfig: ForkScalaRun, atmosInputs: AtmosInputs) extends ScalaRun {
    val forkRun = new ForkRun(forkConfig)

    def run(mainClass: String, classpath: Seq[File], options: Seq[String], log: Logger): Option[String] = {
      log.info("Starting Atmos and Typesafe Console")
      // TODO: add sigar libs to library path
      val atmosOptions = Seq("-Xms512m", "-Xmx512m", "-classpath", Path.makeString(atmosInputs.atmosConfig +: atmosInputs.atmosClasspath.files), "com.typesafe.atmos.AtmosMain", "collect", "analyze", "query")
      // TODO: output directly to log files
      val strategy = forkConfig.outputStrategy getOrElse LoggedOutput(log)
      val atmosProcess = Fork.java.fork(None, atmosOptions, None, Map.empty[String, String], false, strategy)
      val consoleOptions = Seq("-Xms512m", "-Xmx512m", "-classpath", Path.makeString(atmosInputs.consoleConfig +: atmosInputs.consoleClasspath.files), "-Dhttp.port=9000", "play.core.server.NettyServer")
      val consoleProcess = Fork.java.fork(None, consoleOptions, None, Map.empty[String, String], false, strategy)
      // TODO: recognise when atmos and console are up and ready
      Thread.sleep(3000)
      try {
        val cp = (atmosInputs.traceConfig +: atmosInputs.traceClasspath.files) ++ classpath
        forkRun.run(mainClass, cp, options, log)
      } finally {
        log.info("Stopping Atmos and Typesafe Console")
        atmosProcess.destroy()
        consoleProcess.destroy()
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
      if (configuration == Atmos.name || configuration == AtmosConsole.name) {
        (module.organization, module.name) match {
          case (Organization, LibraryID)  => (Artifact(LibraryID), scalaInstance.libraryJar) :: Nil
          case (Organization, CompilerID) => (Artifact(CompilerID), scalaInstance.compilerJar) :: Nil
          case _ => artifacts
        }
      } else artifacts
    }
  }
}
