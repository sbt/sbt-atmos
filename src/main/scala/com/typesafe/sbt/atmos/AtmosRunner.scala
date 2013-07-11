/**
 *  Copyright (C) 2013 Typesafe, Inc <http://typesafe.com>
 */
package com.typesafe.sbt
package atmos

import sbt._
import sbt.Keys._
import sbt.Project.Initialize
import java.net.URI

object AtmosRunner {
  import SbtAtmos.Atmos
  import SbtAtmos.AtmosKeys._

  val Akka20Version = "2.0.5"
  val Akka21Version = "2.1.4"
  val Akka22Version = "2.2.0"

  val AtmosConsole = config("atmos-console") hide
  val AtmosTrace = config("atmos-trace") hide
  val AtmosWeave = config("atmos-weave") hide
  val AtmosSigar = config("atmos-sigar") hide

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
    sigarLibs: Option[File],
    runListeners: Seq[URI => Unit])

  def atmosDependencies(version: String) = Seq(
    "com.typesafe.atmos" % "atmos-dev" % version % Atmos.name
  )

  def consoleDependencies(version: String) = Seq(
    "com.typesafe.console" % "console-solo" % version % AtmosConsole.name
  )

  def traceDependencies(dependencies: Seq[ModuleID], version: String, scalaVersion: String) = {
    if (containsTrace(dependencies)) Seq.empty[ModuleID]
    else if (containsAkka(dependencies, "2.0.")) traceAkkaDependencies(Akka20Version, version, CrossVersion.Disabled)
    else if (containsAkka(dependencies, "2.1.")) traceAkkaDependencies(Akka21Version, version, CrossVersion.Disabled)
    else if (containsAkka(dependencies, "2.2.")) traceAkkaDependencies(Akka22Version, version, akka22CrossVersion(scalaVersion))
    else Seq.empty[ModuleID]
  }

  def containsTrace(dependencies: Seq[ModuleID]): Boolean = dependencies exists { module =>
    module.organization == "com.typesafe.atmos" && module.name.startsWith("trace-akka")
  }

  def containsAkka(dependencies: Seq[ModuleID], versionPrefix: String): Boolean = dependencies exists { module =>
    module.organization == "com.typesafe.akka" && module.name.startsWith("akka-") && module.revision.startsWith(versionPrefix)
  }

  def akka22CrossVersion(scalaVersion: String) = {
    if (scalaVersion startsWith "2.11.0-") CrossVersion.full else CrossVersion.binary
  }

  def traceAkkaDependencies(akkaVersion: String, atmosVersion: String, crossVersion: CrossVersion) = Seq(
    "com.typesafe.atmos" % ("trace-akka-" + akkaVersion) % atmosVersion % AtmosTrace.name cross crossVersion
  )

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

  def selectPort(preferred: Int): Int = {
    var port = preferred
    val limit = preferred + 10
    while (port < limit && busy(port)) port += 1
    if (busy(port)) sys.error("No available port in range [%s-%s]".format(preferred, limit))
    port
  }

  def defaultAtmosConfig(tracePort: Int): String = """
    |akka {
    |  loglevel = INFO
    |  event-handlers = ["akka.event.slf4j.Slf4jEventHandler"]
    |}
    |
    |atmos {
    |  mode = local
    |  trace {
    |    event-handlers = ["com.typesafe.atmos.trace.store.MemoryTraceEventListener", "com.typesafe.atmos.analytics.analyze.LocalAnalyzerTraceEventListener"]
    |    receive.port = %s
    |  }
    |}
  """.trim.stripMargin.format(tracePort)

  def defaultConsoleConfig(name: String, atmosPort: Int): String = """
    |app.name = "%s"
    |app.url="http://localhost:%s/monitoring"
  """.trim.stripMargin.format(name, atmosPort)

  def seqToConfig(seq: Seq[(String, Any)], indent: Int, quote: Boolean): String = {
    seq map { case (k, v) =>
      val indented = " " * indent
      val key = if (quote) "\"%s\"" format k else k
      val value = v
      "%s%s = %s" format (indented, key, value)
    } mkString ("\n")
  }

  def defaultTraceConfig(name: String, traceable: String, sampling: String, tracePort: Int): String = {
    """
      |atmos {
      |  trace {
      |    enabled = true
      |    node = "%s"
      |    traceable {
      |%s
      |    }
      |    sampling {
      |%s
      |    }
      |    send.port = %s
      |  }
      |}
    """.trim.stripMargin.format(name, traceable, sampling, tracePort)
  }

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

  def writeConfig(name: String, configKey: TaskKey[String], logbackKey: SettingKey[String]): Initialize[Task[File]] =
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

  def logConsoleUri(log: Logger)(uri: URI) = {
    log.info("Typesafe Console is available at " + uri)
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
      val atmosRunning = spinUntil(attempts = 50, sleep = 100) { busy(atmosPort) }

      if (!atmosRunning) {
        atmosProcess.destroy()
        sys.error("Could not start Atmos on port [%s]" format atmosPort)
      }

      val allConsoleOptions = consoleOptions ++ Seq(
        "-classpath", Path.makeString(consoleConfig +: consoleClasspath.files),
        "-Dhttp.port=" + consolePort,
        "-Dlogger.resource=/logback.xml",
        "play.core.server.NettyServer"
      )

      val consoleDirectory = IO.createTemporaryDirectory // for pid file
      val consoleProcess = Fork.java.fork(forkConfig.javaHome, allConsoleOptions, Some(consoleDirectory), Map.empty[String, String], false, devNull)
      val consoleRunning = spinUntil(attempts = 50, sleep = 100) { busy(consolePort) }

      if (!consoleRunning) {
        atmosProcess.destroy()
        consoleProcess.destroy()
        IO.delete(consoleDirectory)
        sys.error("Could not start Typesafe Console on port [%s]" format consolePort)
      }

      val consoleUri = new URI("http://localhost:" + consolePort)
      for (listener <- runListeners) listener(consoleUri)

      try {
        val cp = (traceConfig +: traceClasspath.files) ++ classpath
        forkRun.run(mainClass, cp, options, log)
      } finally {
        log.info("Stopping Atmos and Typesafe Console")
        atmosProcess.destroy()
        consoleProcess.destroy()
        IO.delete(consoleDirectory)
      }
    }
  }

  // port checking

  def busy(port: Int): Boolean = {
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

  object NullOutputStream extends java.io.OutputStream {
    override def close(): Unit = ()
    override def flush(): Unit = ()
    override def write(b: Array[Byte]) = ()
    override def write(b: Array[Byte], off: Int, len: Int) = ()
    override def write(b: Int) = ()
  }
}
