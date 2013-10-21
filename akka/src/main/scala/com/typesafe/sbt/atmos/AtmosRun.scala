/**
 *  Copyright (C) 2013 Typesafe, Inc <http://typesafe.com>
 */
package com.typesafe.sbt
package atmos

import sbt._
import sbt.Keys._
import sbt.Project.Initialize
import java.net.{ URI, URLClassLoader }
import org.aspectj.weaver.loadtime.WeavingURLClassLoader

object AtmosRun {
  import AtmosProcess.{ Forked, RunMain }
  import SbtAtmos.Atmos
  import SbtAtmos.AtmosKeys._

  val Akka20Version = "2.0.5"
  val Akka21Version = "2.1.4"
  val Akka22Version = "2.2.1"

  val AtmosTraceCompile = config("atmos-trace-compile").extend(Configurations.RuntimeInternal).hide
  val AtmosTraceTest    = config("atmos-trace-test").extend(AtmosTraceCompile, Configurations.TestInternal).hide

  val AtmosDev     = config("atmos-dev").hide
  val AtmosConsole = config("atmos-console").hide
  val AtmosWeave   = config("atmos-weave").hide
  val AtmosSigar   = config("atmos-sigar").hide

  case class AtmosOptions(port: Int, options: Seq[String], classpath: Classpath)

  case class AtmosInputs(traceOnly: Boolean, tracePort: Int, javaHome: Option[File], atmos: AtmosOptions, console: AtmosOptions, runListeners: Seq[URI => Unit])

  case class Sigar(dependency: Option[File], nativeLibraries: Option[File])

  def targetName(config: Configuration) = {
    "atmos" + (if (config.name == "runtime") "" else "-" + config.name)
  }

  def traceJavaOptions(aspectjWeaver: Option[File], sigarLibs: Option[File]): Seq[String] = {
    val javaAgent = aspectjWeaver.toSeq map { w => "-javaagent:" + w.getAbsolutePath }
    val aspectjOptions = Seq("-Dorg.aspectj.tracing.factory=default")
    val sigarPath = sigarLibs.toSeq map { s => "-Dorg.hyperic.sigar.path=" + s.getAbsolutePath }
    javaAgent ++ aspectjOptions ++ sigarPath
  }

  def atmosDependencies(version: String, useProGuardedVersion: Boolean) = Seq(
    if (useProGuardedVersion) "com.typesafe.atmos" % "atmos-dev" % version % AtmosDev.name
    else "com.typesafe.atmos" % "atmos-query" % version % AtmosDev.name
  )

  def consoleDependencies(version: String, useProGuardedVersion: Boolean) = Seq(
    if (useProGuardedVersion) "com.typesafe.console" % "console-solo" % version % AtmosConsole.name
    else "com.typesafe.console" % "typesafe-console" % version % AtmosConsole.name
  )

  def selectAkkaVersion(dependencies: Seq[ModuleID]): Option[String] = {
    findAkkaVersion(dependencies) map supportedAkkaVersion
  }

  def supportedAkkaVersion(akkaVersion: String): String = {
    if      (akkaVersion startsWith "2.0.") Akka20Version
    else if (akkaVersion startsWith "2.1.") Akka21Version
    else if (akkaVersion startsWith "2.2.") Akka22Version
    else    sys.error("Akka version is not supported by Typesafe Console: " + akkaVersion)
  }

  def selectTraceDependencies(dependencies: Seq[ModuleID], traceAkkaVersion: Option[String], atmosVersion: String, scalaVersion: String): Seq[ModuleID] = {
    if (containsTrace(dependencies)) Seq.empty[ModuleID]
    else traceAkkaVersion match {
      case Some(akkaVersion) => traceAkkaDependencies(akkaVersion, atmosVersion, scalaVersion)
      case None              => Seq.empty[ModuleID]
    }
  }

  def containsTrace(dependencies: Seq[ModuleID]): Boolean = dependencies exists { module =>
    module.organization == "com.typesafe.atmos" && module.name.startsWith("trace-akka")
  }

  def findAkkaVersion(dependencies: Seq[ModuleID]): Option[String] = dependencies find { module =>
    module.organization == "com.typesafe.akka" && module.name.startsWith("akka-")
  } map (_.revision)


  def traceAkkaDependencies(akkaVersion: String, atmosVersion: String, scalaVersion: String): Seq[ModuleID] = {
    val crossVersion = akkaCrossVersion(akkaVersion, scalaVersion)
    Seq("com.typesafe.atmos" % ("trace-akka-" + akkaVersion) % atmosVersion % AtmosTraceCompile.name cross crossVersion)
  }

  def akkaCrossVersion(akkaVersion: String, scalaVersion: String): CrossVersion = {
    if      (akkaVersion startsWith "2.0.") CrossVersion.Disabled
    else if (akkaVersion startsWith "2.1.") CrossVersion.Disabled
    else if (scalaVersion contains "-")     CrossVersion.full
    else                                    CrossVersion.binary
  }

  def weaveDependencies(version: String) = Seq(
    "org.aspectj" % "aspectjweaver" % version % AtmosWeave.name
  )

  def sigarDependencies(version: String) = Seq(
    "com.typesafe.atmos" % "atmos-sigar-libs" % version % AtmosSigar.name
  )

  def collectManagedClasspath(config: Configuration): Initialize[Task[Classpath]] =
    (classpathTypes, update) map { (types, report) => Classpaths.managedJars(config, types, report) }

  def collectTracedClasspath(config: Configuration): Initialize[Task[Classpath]] =
    (classpathTypes, update, streams) map { (types, report, s) =>
      val classpath = Classpaths.managedJars(config, types, report)
      val tracedAkka = classpath count (_.metadata.get(Keys.moduleID.key).map(_.name).getOrElse("").startsWith("trace-akka-"))
      if (tracedAkka < 1) s.log.warn("No trace dependencies for Typesafe Console.")
      if (tracedAkka > 1) s.log.warn("Multiple trace dependencies for Typesafe Console.")
      classpath
    }

  def createClasspath(file: File): Classpath = Seq(Attributed.blank(file))

  def findAspectjWeaver: Initialize[Task[Option[File]]] =
    update map { report => report.matching(moduleFilter(organization = "org.aspectj", name = "aspectjweaver")) headOption }

  def findSigar: Initialize[Task[Option[File]]] =
    update map { report => report.matching(moduleFilter(organization = "org.fusesource", name = "sigar")) headOption }

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
    |atmos.host="localhost"
    |atmos.port=%s
    |atmos.start-url="/monitoring/"
    |query.cache-historical-expiration = 60 seconds
    |query.cache-metadata-expiration = 30 seconds
  """.trim.stripMargin.format(name, atmosPort)

  def seqToConfig(seq: Seq[(String, Any)], indent: Int, quote: Boolean): String = {
    seq map { case (k, v) =>
      val indented = " " * indent
      val key = if (quote) "\"%s\"" format k else k
      val value = v
      "%s%s = %s" format (indented, key, value)
    } mkString ("\n")
  }

  def defaultTraceConfig(node: String, traceable: String, sampling: String, tracePort: Int): String = {
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
      |    send {
      |      port = %s
      |      retry = off
      |    }
      |  }
      |}
    """.trim.stripMargin.format(StringUtilities.normalize(node), traceable, sampling, tracePort)
  }

  def includeAtmosConfig(configs: Seq[String]): String = {
    val includes = configs map { name =>
      """
        |%s {
        |  include "atmos"
        |}
      """.trim.stripMargin.format(name)
    } mkString ("\n")

    """
      |include "atmos"
      |%s
    """.trim.stripMargin.format(includes)
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
    (atmosConfigDirectory, configKey, logbackKey) map { (confDir, conf, logback) =>
      writeConfigFiles(confDir, name, Seq(
        "application.conf" -> conf,
        "logback.xml" -> logback
      ))
    }

  def writeTraceConfig(name: String, configKey: TaskKey[String], includesKey: TaskKey[String]): Initialize[Task[File]] =
    (atmosConfigDirectory, configKey, includesKey) map { (confDir, conf, includes) =>
      val configResource = sys.props.getOrElse("config.resource", "application.conf")
      writeConfigFiles(confDir, name, Seq(
        "atmos.conf" -> conf,
        configResource -> includes
      ))
    }

  def writeConfigFiles(base: File, name: String, configs: Seq[(String, String)]): File = {
    val dir = base / name
    for ((filename, content) <- configs) {
      if (content.nonEmpty) IO.write(dir / filename, content)
    }
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
    (baseDirectory, javaOptions, outputStrategy, fork, trapExit, connectInput, traceOptions, sigar, atmosInputs) map {
      (base, options, strategy, forkRun, trap, connectIn, traceOpts, sigar, inputs) =>
        if (forkRun) {
          val forkConfig = ForkOptions(inputs.javaHome, strategy, Seq.empty, Some(base), options ++ traceOpts, connectIn)
          new AtmosForkRun(forkConfig, inputs)
        } else {
          new AtmosDirectRun(trap, sigar, inputs)
        }
    }

  class AtmosForkRun(forkConfig: ForkScalaRun, inputs: AtmosInputs) extends AtmosRunner(inputs) {
    def atmosRun(mainClass: String, classpath: Seq[File], options: Seq[String], log: Logger): Option[String] = {
      log.info("Running (forked) " + mainClass + " " + options.mkString(" "))
      log.debug("  Classpath:\n\t" + classpath.mkString("\n\t"))
      val forkRun = new Forked(mainClass, forkConfig, temporary = false)
      val exitCode = forkRun.run(mainClass, classpath, options, log).exitValue()
      forkRun.cancelShutdownHook()
      if (exitCode == 0) None
      else Some("Nonzero exit code returned from runner: " + exitCode)
    }
  }

  object SigarClassLoader {
    private var sigarLoader: Option[ClassLoader] = None

    def apply(sigar: Sigar): ClassLoader = synchronized {
      if (sigarLoader.isDefined) {
        sigarLoader.get
      } else if (sigar.dependency.isEmpty || sigar.nativeLibraries.isEmpty) {
        null
      } else {
        sigar.nativeLibraries foreach { s => System.setProperty("org.hyperic.sigar.path", s.getAbsolutePath) }
        val loader = new URLClassLoader(Path.toURLs(sigar.dependency.toSeq), null)
        sigarLoader = Some(loader)
        loader
      }
    }
  }

  class AtmosDirectRun(trapExit: Boolean, sigar: Sigar, inputs: AtmosInputs) extends AtmosRunner(inputs) {
    def atmosRun(mainClass: String, classpath: Seq[File], options: Seq[String], log: Logger): Option[String] = {
      log.info("Running " + mainClass + " " + options.mkString(" "))
      log.debug("  Classpath:\n\t" + classpath.mkString("\n\t"))
      System.setProperty("org.aspectj.tracing.factory", "default")
      val loader = new WeavingURLClassLoader(Path.toURLs(classpath), SigarClassLoader(sigar))
      (new RunMain(loader, mainClass, options)).run(trapExit, log)
    }
  }

  abstract class AtmosRunner(inputs: AtmosInputs) extends ScalaRun {
    def atmosRun(mainClass: String, classpath: Seq[File], arguments: Seq[String], log: Logger): Option[String]

    def run(mainClass: String, classpath: Seq[File], arguments: Seq[String], log: Logger): Option[String] = {
      try {
        AtmosController.start(inputs, log)
        atmosRun(mainClass, classpath, arguments, log)
      } finally {
        AtmosController.stop(log)
      }
    }
  }

  def atmosLauncher: Initialize[Task[ScalaRun]] =
    (baseDirectory, javaOptions, outputStrategy, traceOptions, atmosInputs, node, launchNode) map {
      (base, options, strategy, traceOpts, inputs, defaultName, nodeNamer) =>
        val forkConfig = ForkOptions(inputs.javaHome, strategy, Seq.empty, Some(base), options ++ traceOpts, connectInput = false)
        new AtmosLaunch(forkConfig, inputs, defaultName, nodeNamer)
    }

  class AtmosLaunch(forkConfig: ForkOptions, inputs: AtmosInputs, defaultName: String, nodeNamer: NodeNamer) extends ScalaRun {
    def run(mainClass: String, classpath: Seq[File], options: Seq[String], log: Logger): Option[String] = {
      AtmosController.start(inputs, log)
      log.info("Launching " + mainClass + " " + options.mkString(" "))
      log.debug("  Classpath:\n\t" + classpath.mkString("\n\t"))
      val node = nodeNamer(defaultName, mainClass, options)
      val nodeProperty = "-Datmos.trace.node=" + node
      val nodeConfig = forkConfig.copy(runJVMOptions = (forkConfig.runJVMOptions :+ nodeProperty))
      val name = "%s (%s)" format (node, mainClass)
      val forked = new Forked(name, nodeConfig, temporary = false)
      forked.run(mainClass, classpath, options, log)
      AtmosController.launched(forked)
      None
    }
  }
}
