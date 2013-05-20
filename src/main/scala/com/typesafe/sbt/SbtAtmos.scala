/**
 *  Copyright (C) 2013 Typesafe, Inc <http://typesafe.com>
 */
package com.typesafe.sbt

import sbt._
import sbt.Keys._
import sbt.Project.Initialize

object SbtAtmos extends Plugin {

  case class AtmosInputs(
    atmosPort: Int,
    consolePort: Int,
    atmosOptions: Seq[String],
    consoleOptions: Seq[String],
    atmosClasspath: Classpath,
    consoleClasspath: Classpath,
    traceClasspath: Classpath,
    aspectjWeaver: Option[File],
    atmosDirectory: File,
    atmosConfig: File,
    consoleConfig: File,
    traceConfig: File,
    sigarLibs: Option[File])

  val Atmos = config("atmos") hide
  val AtmosConsole = config("atmos-console") hide
  val AtmosTrace = config("atmos-trace") hide
  val AtmosWeave = config("atmos-weave") hide
  val AtmosSigar = config("atmos-sigar") hide

  object AtmosKeys {
    val atmosVersion = SettingKey[String]("atmos-version")
    val aspectjVersion = SettingKey[String]("aspectj-version")

    val atmosPort = SettingKey[Int]("atmos-port")
    val consolePort = SettingKey[Int]("console-port")

    val atmosOptions = TaskKey[Seq[String]]("atmos-options")
    val consoleOptions = TaskKey[Seq[String]]("console-options")

    val atmosClasspath = TaskKey[Classpath]("atmos-classpath")
    val consoleClasspath = TaskKey[Classpath]("console-classpath")
    val traceClasspath = TaskKey[Classpath]("trace-classpath")
    val aspectjWeaver = TaskKey[Option[File]]("aspectj-weaver")

    val atmosDirectory = SettingKey[File]("atmos-directory")
    val atmosConfigDirectory = SettingKey[File]("atmos-config-directory")
    val atmosLogDirectory = SettingKey[File]("atmos-log-directory")
    val atmosConfigString = SettingKey[String]("atmos-config-string")
    val atmosLogbackString = SettingKey[String]("atmos-logback-string")
    val atmosConfig = TaskKey[File]("atmos-config")
    val consoleConfigString = SettingKey[String]("console-config-string")
    val consoleLogbackString = SettingKey[String]("console-logback-string")
    val consoleConfig = TaskKey[File]("console-config")
    val traceConfigString = SettingKey[String]("trace-config-string")
    val traceLogbackString = SettingKey[String]("trace-logback-string")
    val traceConfig = TaskKey[File]("trace-config")

    val sigarLibs = TaskKey[Option[File]]("sigar-libs")

    val atmosInputs = TaskKey[AtmosInputs]("atmos-inputs")
  }

  import AtmosKeys._

  lazy val atmosSettings: Seq[Setting[_]] = inConfig(Atmos)(atmosScopedSettings) ++ atmosUnscopedSettings

  def atmosScopedSettings: Seq[Setting[_]] = Seq(
    atmosVersion := "1.2.0-M4",
    aspectjVersion := "1.7.2",

    atmosPort := 8667,
    consolePort := 9900,

    atmosOptions := Seq("-Xms512m", "-Xmx512m"),
    consoleOptions := Seq("-Xms512m", "-Xmx512m"),

    atmosClasspath <<= managedClasspath(Atmos),
    consoleClasspath <<= managedClasspath(AtmosConsole),
    traceClasspath <<= managedClasspath(AtmosTrace),
    aspectjWeaver <<= findAspectjWeaver,

    atmosDirectory <<= target / "atmos",
    atmosConfigDirectory <<= atmosDirectory / "conf",
    atmosLogDirectory <<= atmosDirectory / "log",
    atmosConfigString := defaultAtmosConfig,
    atmosLogbackString <<= defaultLogbackConfig("atmos"),
    atmosConfig <<= writeConfig("atmos", atmosConfigString, atmosLogbackString),
    consoleConfigString <<= (name, atmosPort) apply defaultConsoleConfig,
    consoleLogbackString <<= defaultLogbackConfig("console"),
    consoleConfig <<= writeConfig("console", consoleConfigString, consoleLogbackString),
    traceConfigString <<= normalizedName apply defaultTraceConfig,
    traceLogbackString := "",
    traceConfig <<= writeConfig("trace", traceConfigString, traceLogbackString),

    sigarLibs <<= unpackSigar,

    atmosInputs <<= (
      atmosPort, consolePort, atmosOptions, consoleOptions,
      atmosClasspath, consoleClasspath, traceClasspath, aspectjWeaver,
      atmosDirectory, atmosConfig, consoleConfig, traceConfig, sigarLibs
    ) map AtmosInputs,

    inScope(Scope(This, Select(Compile), Select(run.key), This))(Seq(runner in run in Atmos <<= atmosRunner)).head,
    run <<= Defaults.runTask(fullClasspath in Runtime, mainClass in run in Compile, runner in run)
  )

  def atmosUnscopedSettings: Seq[Setting[_]] = Seq(
    ivyConfigurations ++= Seq(Atmos, AtmosConsole, AtmosTrace, AtmosWeave, AtmosSigar),

    resolvers += "Typesafe Releases" at "http://repo.typesafe.com/typesafe/releases",

    libraryDependencies <++= (atmosVersion in Atmos)(atmosDependencies),
    libraryDependencies <++= (atmosVersion in Atmos)(consoleDependencies),
    libraryDependencies <++= (libraryDependencies, atmosVersion in Atmos)(traceDependencies),
    libraryDependencies <++= (aspectjVersion in Atmos)(weaveDependencies),
    libraryDependencies <++= (atmosVersion in Atmos)(sigarDependencies)
  )

  def atmosDependencies(version: String) = Seq(
    "com.typesafe.atmos" % "atmos-dev" % version % Atmos.name
  )

  def consoleDependencies(version: String) = Seq(
    "com.typesafe.console" % "console-solo" % version % AtmosConsole.name
  )

  def traceDependencies(dependencies: Seq[ModuleID], version: String) = {
    if (containsTrace(dependencies)) Seq.empty[ModuleID]
    else if (containsAkka21(dependencies)) Seq("com.typesafe.atmos" % "trace-akka-2.1.2" % version % AtmosTrace.name)
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

  def sigarDependencies(version: String) = Seq(
    "com.typesafe.atmos" % "atmos-sigar-libs" % version % AtmosSigar.name
  )

  def managedClasspath(config: Configuration): Initialize[Task[Classpath]] =
    (classpathTypes, update) map { (types, report) => Classpaths.managedJars(config, types, report) }

  def findAspectjWeaver: Initialize[Task[Option[File]]] =
    update map { report => report.matching(moduleFilter(organization = "org.aspectj", name = "aspectjweaver")) headOption }

  def defaultAtmosConfig(): String = """
    |akka {
    |  loglevel = INFO
    |  event-handlers = ["akka.event.slf4j.Slf4jEventHandler"]
    |}
    |
    |atmos {
    |  mode = local
    |  trace {
    |    event-handlers = ["com.typesafe.atmos.trace.store.MemoryTraceEventListener", "com.typesafe.atmos.analytics.analyze.LocalAnalyzerTraceEventListener"]
    |  }
    |}
  """.trim.stripMargin

  def defaultConsoleConfig(name: String, atmosPort: Int): String = """
    |app.name = "%s"
    |app.url="http://localhost:%s/monitoring"
  """.trim.stripMargin.format(name, atmosPort.toString)

  def defaultTraceConfig(name: String): String = """
    |atmos {
    |  trace {
    |    enabled = true
    |    node = "%s"
    |    traceable {
    |      "*" = on
    |    }
    |    sampling {
    |      "*" = 1
    |    }
    |  }
    |}
  """.trim.stripMargin.format(name)

  def defaultLogbackConfig(name: String): Initialize[String] = atmosLogDirectory { dir =>
    """
      |<?xml version="1.0" encoding="UTF-8"?>
      |<configuration scan="false" debug="false">
      |  <property scope="local" name="logDir" value="%s"/>
      |  <property scope="local" name="logName" value="%s"/>
    """.trim.stripMargin.format(dir.getAbsolutePath, name) + """
      |  <appender name="file" class="ch.qos.logback.core.rolling.RollingFileAppender">
      |    <File>${logDir}/${logName}.log</File>
      |    <encoder>
      |      <pattern>%date{ISO8601} %-5level [%logger{36}] [%X{akkaSource}] [%X{sourceThread}] : %m%n</pattern>
      |    </encoder>
      |    <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
      |      <fileNamePattern>${logDir}/${logName}.log.%d{yyyy-MM-dd-HH}</fileNamePattern>
      |    </rollingPolicy>
      |  </appender>
      |  <root level="INFO">
      |    <appender-ref ref="file"/>
      |  </root>
      |</configuration>
    """.stripMargin
  }

  def writeConfig(name: String, configKey: SettingKey[String], logbackKey: SettingKey[String]): Initialize[Task[File]] =
    (atmosConfigDirectory in Atmos, configKey, logbackKey) map { (confDir, conf, logback) =>
      val dir = confDir / name
      val confFile = dir / "application.conf"
      val logbackFile = dir / "logback.xml"
      if (conf.nonEmpty) IO.write(confFile, conf)
      if (logback.nonEmpty) IO.write(logbackFile, logback)
      dir
    }

  def unpackSigar: Initialize[Task[Option[File]]] = (update, atmosDirectory) map { (report, dir) =>
    report.matching(moduleFilter(name = "atmos-sigar-libs")).headOption map { jar =>
      val unzipped = dir / "sigar"
      IO.unzip(jar, unzipped)
      unzipped
    }
  }

  def atmosRunner: Initialize[Task[ScalaRun]] =
    (scalaInstance, baseDirectory, javaOptions, outputStrategy, javaHome, connectInput, atmosInputs in Atmos) map {
      (si, base, options, strategy, javaHomeDir, connectIn, inputs) =>
        val javaAgent = inputs.aspectjWeaver.toSeq map { w => "-javaagent:" + w.getAbsolutePath }
        val javaLibraryPath = inputs.sigarLibs.toSeq map { s => "-Djava.library.path=" + s.getAbsolutePath }
        val aspectjOptions = Seq("-Dorg.aspectj.tracing.factory=default")
        val atmosOptions = javaAgent ++ javaLibraryPath ++ aspectjOptions
        val forkConfig = ForkOptions(javaHomeDir, strategy, si.jars, Some(base), options ++ atmosOptions, connectIn)
        new AtmosRun(forkConfig, inputs)
    }

  class AtmosRun(forkConfig: ForkScalaRun, atmosInputs: AtmosInputs) extends ScalaRun {
    val forkRun = new ForkRun(forkConfig)

    def run(mainClass: String, classpath: Seq[File], options: Seq[String], log: Logger): Option[String] = {
      import atmosInputs._
      log.info("Starting Atmos and Typesafe Console ...")

      val devNull = CustomOutput(NullOutputStream)

      val allAtmosOptions = atmosOptions ++ Seq(
        "-classpath", Path.makeString(atmosConfig +: atmosClasspath.files),
        "-Dquery.http.port=" + atmosPort,
        "com.typesafe.atmos.AtmosDev"
      )

      val atmosProcess = Fork.java.fork(forkConfig.javaHome, allAtmosOptions, Some(atmosDirectory), Map.empty[String, String], false, devNull)

      val atmosRunning = spinUntil(attempts = 50, sleep = 100) { checkPort(atmosPort) }

      if (!atmosRunning) {
        atmosProcess.destroy()
        sys.error("Could not start Atmos")
      }

      val allConsoleOptions = consoleOptions ++ Seq(
        "-classpath", Path.makeString(consoleConfig +: consoleClasspath.files),
        "-Dhttp.port=" + consolePort,
        "-Dlogger.resource=/logback.xml",
        "play.core.server.NettyServer"
      )

      val consoleProcess = Fork.java.fork(forkConfig.javaHome, allConsoleOptions, Some(atmosDirectory), Map.empty[String, String], false, devNull)

      val consoleRunning = spinUntil(attempts = 50, sleep = 100) { checkPort(consolePort) }

      if (!consoleRunning) {
        consoleProcess.destroy()
        sys.error("Could not start Typesafe Console")
      }

      log.info("Typesafe Console is available at http://localhost:" + consolePort)

      try {
        val cp = (traceConfig +: traceClasspath.files) ++ classpath
        forkRun.run(mainClass, cp, options, log)
      } finally {
        log.info("Stopping Atmos and Typesafe Console")
        atmosProcess.destroy()
        consoleProcess.destroy()
      }
    }

    // port checking

    def checkPort(port: Int): Boolean = {
      try {
        val socket = new java.net.Socket("localhost", port)
        socket.close()
        true
      } catch {
        case _: java.io.IOException => false
      }
    }

    def spinUntil(attempts: Int, sleep: Long)(test: => Boolean): Boolean = {
      var n = 1
      var success = false
      while(n <= attempts && !success) {
        success = test
        if (!success) Thread.sleep(sleep)
        n += 1
      }
      success
    }
  }

  object NullOutputStream extends java.io.OutputStream {
    override def close(): Unit = ()
    override def flush(): Unit = ()
    override def write(b: Array[Byte]) = ()
    override def write(b: Array[Byte], off: Int, len: Int) = ()
    override def write(b: Int) = ()
  }
}
