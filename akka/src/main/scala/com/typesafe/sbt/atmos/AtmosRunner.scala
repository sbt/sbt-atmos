/**
 *  Copyright (C) 2013 Typesafe, Inc <http://typesafe.com>
 */
package com.typesafe.sbt
package atmos

import sbt._
import sbt.Keys._
import sbt.Project.Initialize

import java.io.File.pathSeparator
import java.lang.reflect.{ Method, Modifier }
import java.lang.{ Runtime => JRuntime }
import java.net.{ URI, URLClassLoader }
import org.aspectj.weaver.loadtime.WeavingURLClassLoader

object AtmosRunner {
  import SbtAtmos.Atmos
  import SbtAtmos.AtmosKeys._

  val Akka20Version = "2.0.5"
  val Akka21Version = "2.1.4"
  val Akka22Version = "2.2.0"

  val AtmosTraceCompile = config("atmos-trace-compile").extend(Configurations.RuntimeInternal).hide
  val AtmosTraceTest    = config("atmos-trace-test").extend(AtmosTraceCompile, Configurations.TestInternal).hide

  val AtmosDev     = config("atmos-dev").hide
  val AtmosConsole = config("atmos-console").hide
  val AtmosWeave   = config("atmos-weave").hide
  val AtmosSigar   = config("atmos-sigar").hide

  case class AtmosOptions(port: Int, options: Seq[String], classpath: Classpath)

  case class AtmosInputs(traceOnly: Boolean, atmos: AtmosOptions, console: AtmosOptions, runListeners: Seq[URI => Unit])

  case class Sigar(dependency: Option[File], nativeLibraries: Option[File])

  def targetName(config: Configuration) = {
    "atmos" + (if (config.name == "compile") "" else "-" + config.name)
  }

  def traceJavaOptions(aspectjWeaver: Option[File], sigarLibs: Option[File]): Seq[String] = {
    val javaAgent = aspectjWeaver.toSeq map { w => "-javaagent:" + w.getAbsolutePath }
    val aspectjOptions = Seq("-Dorg.aspectj.tracing.factory=default")
    val sigarPath = sigarLibs.toSeq map { s => "-Dorg.hyperic.sigar.path=" + s.getAbsolutePath }
    javaAgent ++ aspectjOptions ++ sigarPath
  }

  def atmosDependencies(version: String) = Seq(
    "com.typesafe.atmos" % "atmos-dev" % version % AtmosDev.name
  )

  def consoleDependencies(version: String) = Seq(
    "com.typesafe.console" % "console-solo" % version % AtmosConsole.name
  )

  def selectTraceDependencies(dependencies: Seq[ModuleID], atmosVersion: String, scalaVersion: String): Seq[ModuleID] = {
    if (containsTrace(dependencies)) Seq.empty[ModuleID]
    else findAkkaVersion(dependencies) match {
      case Some(akkaVersion) => traceDependencies(akkaVersion)(atmosVersion, scalaVersion)
      case None              => Seq.empty[ModuleID]
    }
  }

  def containsTrace(dependencies: Seq[ModuleID]): Boolean = dependencies exists { module =>
    module.organization == "com.typesafe.atmos" && module.name.startsWith("trace-akka")
  }

  def findAkkaVersion(dependencies: Seq[ModuleID]): Option[String] = dependencies find { module =>
    module.organization == "com.typesafe.akka" && module.name.startsWith("akka-")
  } map (_.revision)


  def traceDependencies(akkaVersion: String)(atmosVersion: String, scalaVersion: String): Seq[ModuleID] = {
    val (supportedAkkaVersion, crossVersion) = selectAkkaVersion(akkaVersion, scalaVersion)
    traceAkkaDependencies(supportedAkkaVersion, atmosVersion, crossVersion)
  }

  def selectAkkaVersion(akkaVersion: String, scalaVersion: String): (String, CrossVersion) = {
    if      (akkaVersion startsWith "2.0.") (Akka20Version, CrossVersion.Disabled)
    else if (akkaVersion startsWith "2.1.") (Akka21Version, CrossVersion.Disabled)
    else if (akkaVersion startsWith "2.2.") (Akka22Version, akka22CrossVersion(scalaVersion))
    else    sys.error("Akka version is not supported by Atmos: " + akkaVersion)
  }

  def akka22CrossVersion(scalaVersion: String) = {
    if (scalaVersion startsWith "2.11.0-") CrossVersion.full else CrossVersion.binary
  }

  def traceAkkaDependencies(akkaVersion: String, atmosVersion: String, crossVersion: CrossVersion) = Seq(
    "com.typesafe.atmos" % ("trace-akka-" + akkaVersion) % atmosVersion % AtmosTraceCompile.name cross crossVersion
  )

  def weaveDependencies(version: String) = Seq(
    "org.aspectj" % "aspectjweaver" % version % AtmosWeave.name
  )

  def sigarDependencies(version: String) = Seq(
    "com.typesafe.atmos" % "atmos-sigar-libs" % version % AtmosSigar.name
  )

  def collectManagedClasspath(config: Configuration): Initialize[Task[Classpath]] =
    (classpathTypes, update) map { (types, report) => Classpaths.managedJars(config, types, report) }

  def createClasspath(file: File): Classpath = Seq(Attributed.blank(file))

  def findAspectjWeaver: Initialize[Task[Option[File]]] =
    update map { report => report.matching(moduleFilter(organization = "org.aspectj", name = "aspectjweaver")) headOption }

  def findSigar: Initialize[Task[Option[File]]] =
    update map { report => report.matching(moduleFilter(organization = "org.fusesource", name = "sigar")) headOption }

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
      |    send {
      |      port = %s
      |      daemonic = true
      |    }
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
    (atmosConfigDirectory, configKey, logbackKey) map { (confDir, conf, logback) =>
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
    (baseDirectory, javaOptions, outputStrategy, fork, javaHome, trapExit, connectInput, traceOptions, sigar, atmosInputs) map {
      (base, options, strategy, forkRun, javaHomeDir, trap, connectIn, traceOpts, sigar, inputs) =>
        if (forkRun) {
          val forkConfig = ForkOptions(javaHomeDir, strategy, Seq.empty, Some(base), options ++ traceOpts, connectIn)
          new AtmosForkRun(forkConfig, inputs)
        } else {
          new AtmosDirectRun(trap, sigar, javaHomeDir, inputs)
        }
    }

  class AtmosForkRun(forkConfig: ForkScalaRun, inputs: AtmosInputs) extends AtmosRun(forkConfig.javaHome, inputs) {
    def atmosRun(mainClass: String, classpath: Seq[File], options: Seq[String], log: Logger): Option[String] = {
      log.info("Running (forked) " + mainClass + " " + options.mkString(" "))
      log.debug("  Classpath:\n\t" + classpath.mkString("\n\t"))
      val forkRun = new Forked(mainClass, forkConfig, temporary = false, log)
      val exitCode = forkRun.run(mainClass, classpath, options).exitValue()
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

  class AtmosDirectRun(trapExit: Boolean, sigar: Sigar, javaHome: Option[File], inputs: AtmosInputs) extends AtmosRun(javaHome, inputs) {
    def atmosRun(mainClass: String, classpath: Seq[File], options: Seq[String], log: Logger): Option[String] = {
      log.info("Running " + mainClass + " " + options.mkString(" "))
      log.debug("  Classpath:\n\t" + classpath.mkString("\n\t"))
      System.setProperty("org.aspectj.tracing.factory", "default")
      val loader = new WeavingURLClassLoader(Path.toURLs(classpath), SigarClassLoader(sigar))
      (new RunMain(loader, mainClass, options)).run(trapExit, log)
    }
  }

  abstract class AtmosRun(javaHome: Option[File], inputs: AtmosInputs) extends ScalaRun {
    def atmosRun(mainClass: String, classpath: Seq[File], arguments: Seq[String], log: Logger): Option[String]

    def run(mainClass: String, classpath: Seq[File], arguments: Seq[String], log: Logger): Option[String] = {
      if (!classpath.exists(_.name.startsWith("trace-akka-"))) {
        log.warn("No trace dependencies for Atmos. See sbt-atmos readme for more information.")
      }

      val atmos = new AtmosController(javaHome, inputs, log)

      try {
        atmos.start()
        atmosRun(mainClass, classpath, arguments, log)
      } finally {
        atmos.stop()
      }
    }
  }

  class Forked(name: String, config: ForkScalaRun, temporary: Boolean, log: Logger) {
    private var workingDirectory: Option[File] = None
    private var process: Process = _
    private var shutdownHook: Thread = _

    def run(mainClass: String, classpath: Seq[File], options: Seq[String] = Seq.empty): Forked = synchronized {
      val javaOptions = config.runJVMOptions ++ Seq("-classpath", Path.makeString(classpath), mainClass) ++ options
      val strategy = config.outputStrategy getOrElse LoggedOutput(log)
      workingDirectory = if (temporary) Some(IO.createTemporaryDirectory) else config.workingDirectory
      shutdownHook = new Thread(new Runnable { def run(): Unit = destroy() })
      JRuntime.getRuntime.addShutdownHook(shutdownHook)
      process = Fork.java.fork(config.javaHome, javaOptions, workingDirectory, Map.empty[String, String], config.connectInput, strategy)
      this
    }

    def exitValue(): Int = {
      if (process ne null) {
        try process.exitValue()
        catch { case e: InterruptedException => destroy(); 1 }
      } else 0
    }

    def stop(): Unit = synchronized {
      cancelShutdownHook()
      destroy()
    }

    def destroy(): Unit = synchronized {
      if (process ne null) {
        log.info("Stopping " + name)
        process.destroy()
        process = null.asInstanceOf[Process]
        if (temporary) {
          workingDirectory foreach IO.delete
          workingDirectory = None
        }
      }
    }

    def cancelShutdownHook(): Unit = synchronized {
      if (shutdownHook ne null) {
        JRuntime.getRuntime.removeShutdownHook(shutdownHook)
        shutdownHook = null.asInstanceOf[Thread]
      }
    }
  }

  class RunMain(loader: ClassLoader, mainClass: String, options: Seq[String]) {
    def run(trapExit: Boolean, log: Logger): Option[String] = {
      if (trapExit) {
        Run.executeTrapExit(runMain, log)
      } else {
        try { runMain; None }
        catch { case e: Exception => log.trace(e); Some(e.toString) }
      }
    }

    def runMain(): Unit = {
      try {
        val main = getMainMethod(mainClass, loader)
        invokeMain(loader, main, options)
      } catch {
        case e: java.lang.reflect.InvocationTargetException => throw e.getCause
      }
    }

    def getMainMethod(mainClass: String, loader: ClassLoader): Method = {
      val main = Class.forName(mainClass, true, loader)
      val method = main.getMethod("main", classOf[Array[String]])
      val modifiers = method.getModifiers
      if (!Modifier.isPublic(modifiers)) throw new NoSuchMethodException(mainClass + ".main is not public")
      if (!Modifier.isStatic(modifiers)) throw new NoSuchMethodException(mainClass + ".main is not static")
      method
    }

    def invokeMain(loader: ClassLoader, main: Method, options: Seq[String]): Unit = {
      val currentThread = Thread.currentThread
      val oldLoader = currentThread.getContextClassLoader()
      currentThread.setContextClassLoader(loader)
      try { main.invoke(null, options.toArray[String].asInstanceOf[Array[String]] ) }
      finally { currentThread.setContextClassLoader(oldLoader) }
    }
  }

  class AtmosController(javaHome: Option[File], inputs: AtmosInputs, log: Logger) {
    private var atmos: Forked = _
    private var console: Forked = _

    def start(): Unit = {
      if (!inputs.traceOnly) startAtmos()
    }

    def startAtmos(): Unit = {
      log.info("Starting Atmos and Typesafe Console ...")

      val devNull = Some(LoggedOutput(DevNullLogger))

      val atmosMain = "com.typesafe.atmos.AtmosDev"
      val atmosCp = inputs.atmos.classpath.files
      val atmosPort = inputs.atmos.port
      val atmosJVMOptions = inputs.atmos.options ++ Seq("-Dquery.http.port=" + atmosPort)
      val atmosForkConfig = ForkOptions(javaHome = javaHome, outputStrategy = devNull, runJVMOptions = atmosJVMOptions)
      atmos = new Forked("Atmos", atmosForkConfig, temporary = true, log).run(atmosMain, atmosCp)

      val atmosRunning = spinUntil(attempts = 50, sleep = 100) { busy(atmosPort) }

      if (!atmosRunning) {
        atmos.stop()
        sys.error("Could not start Atmos on port [%s]" format atmosPort)
      }

      val consoleMain = "play.core.server.NettyServer"
      val consoleCp = inputs.console.classpath.files
      val consolePort = inputs.console.port
      val consoleJVMOptions = inputs.console.options ++ Seq("-Dhttp.port=" + consolePort, "-Dlogger.resource=/logback.xml")
      val consoleForkConfig = ForkOptions(javaHome = javaHome, outputStrategy = devNull, runJVMOptions = consoleJVMOptions)
      console = new Forked("Typesafe Console", consoleForkConfig, temporary = true, log).run(consoleMain, consoleCp)

      val consoleRunning = spinUntil(attempts = 50, sleep = 100) { busy(consolePort) }

      if (!consoleRunning) {
        atmos.stop()
        console.stop()
        sys.error("Could not start Typesafe Console on port [%s]" format consolePort)
      }

      val consoleUri = new URI("http://localhost:" + consolePort)
      for (listener <- inputs.runListeners) listener(consoleUri)
    }

    def stop(): Unit = {
      if (!inputs.traceOnly) stopAtmos()
    }

    def stopAtmos(): Unit = {
      if (atmos ne null) atmos.stop()
      if (console ne null) console.stop()
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

  object DevNullLogger extends Logger {
    def trace(t: => Throwable): Unit = ()
    def success(message: => String): Unit = ()
    def log(level: Level.Value, message: => String): Unit = ()
  }
}
