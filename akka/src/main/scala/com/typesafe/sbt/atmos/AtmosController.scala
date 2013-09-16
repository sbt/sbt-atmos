/**
 *  Copyright (C) 2013 Typesafe, Inc <http://typesafe.com>
 */
package com.typesafe.sbt
package atmos

import sbt._
import AtmosProcess.Forked
import AtmosRun.AtmosInputs

object AtmosController {
  private var global: Option[AtmosController] = None
  private var explicitlyStarted: Boolean = false
  private var launchedRuns: Seq[Forked] = Seq.empty

  def start(inputs: AtmosInputs, log: Logger, explicit: Boolean = false): Unit = synchronized {
    if (global.isEmpty) {
      val controller = new AtmosController(inputs)
      controller.start(log)
      explicitlyStarted = explicit
      global = Some(controller)
    }
  }

  def launched(forked: Forked): Unit = synchronized {
    launchedRuns :+= forked
  }

  def stop(log: Logger, explicit: Boolean = false): Unit = synchronized {
    if (explicit || !explicitlyStarted) {
      launchedRuns foreach (_.stop(log))
      launchedRuns = Seq.empty
      global foreach (_.stop(log))
      global = None
    }
  }

  // port selection and checking

  def tracePort(): Option[Int] = synchronized {
    global map (_.inputs.tracePort)
  }

  def selectTracePort(default: Int): Int = {
    tracePort getOrElse selectPort(default)
  }

  def selectPort(preferred: Int): Int = {
    var port = preferred
    val limit = preferred + 10
    while (port < limit && busy(port)) port += 1
    if (busy(port)) sys.error("No available port in range [%s-%s]".format(preferred, limit))
    port
  }

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
}

class AtmosController(val inputs: AtmosInputs) {
  import AtmosController.{ busy, spinUntil }

  private var atmos: Forked = _
  private var console: Forked = _

  def start(log: Logger): Unit = {
    if (!inputs.traceOnly) startAtmos(log)
  }

  def startAtmos(log: Logger): Unit = {
    log.info("Starting Atmos and Typesafe Console ...")

    val devNull = Some(LoggedOutput(DevNullLogger))

    val atmosMain = "com.typesafe.atmos.AtmosDev"
    val atmosCp = inputs.atmos.classpath.files
    val atmosPort = inputs.atmos.port
    val atmosJVMOptions = inputs.atmos.options ++ Seq("-Dquery.http.port=" + atmosPort)
    val atmosForkConfig = ForkOptions(javaHome = inputs.javaHome, outputStrategy = devNull, runJVMOptions = atmosJVMOptions)
    atmos = new Forked("Atmos", atmosForkConfig, temporary = true).run(atmosMain, atmosCp, Seq.empty, log)

    val atmosRunning = spinUntil(attempts = 50, sleep = 100) { busy(atmosPort) }

    if (!atmosRunning) {
      atmos.stop(log)
      sys.error("Could not start Atmos on port [%s]" format atmosPort)
    }

    val consoleMain = "play.core.server.NettyServer"
    val consoleCp = inputs.console.classpath.files
    val consolePort = inputs.console.port
    val consoleJVMOptions = inputs.console.options ++ Seq("-Dhttp.port=" + consolePort, "-Dlogger.resource=/logback.xml")
    val consoleForkConfig = ForkOptions(javaHome = inputs.javaHome, outputStrategy = devNull, runJVMOptions = consoleJVMOptions)
    console = new Forked("Typesafe Console", consoleForkConfig, temporary = true).run(consoleMain, consoleCp, Seq.empty, log)

    val consoleRunning = spinUntil(attempts = 50, sleep = 100) { busy(consolePort) }

    if (!consoleRunning) {
      atmos.stop(log)
      console.stop(log)
      sys.error("Could not start Typesafe Console on port [%s]" format consolePort)
    }

    val consoleUri = new URI("http://localhost:" + consolePort)
    for (listener <- inputs.runListeners) listener(consoleUri)
  }

  def stop(log: Logger): Unit = {
    if (!inputs.traceOnly) stopAtmos(log)
  }

  def stopAtmos(log: Logger): Unit = {
    if (atmos ne null) atmos.stop(log)
    if (console ne null) console.stop(log)
  }
}

object DevNullLogger extends Logger {
  def trace(t: => Throwable): Unit = ()
  def success(message: => String): Unit = ()
  def log(level: Level.Value, message: => String): Unit = ()
}
