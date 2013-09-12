/**
 *  Copyright (C) 2013 Typesafe, Inc <http://typesafe.com>
 */
package com.typesafe.sbt
package atmos

import sbt._
import java.lang.reflect.{ Method, Modifier }
import java.lang.{ Runtime => JRuntime }

object AtmosProcess {
  class Forked(name: String, config: ForkScalaRun, temporary: Boolean) {
    private var workingDirectory: Option[File] = None
    private var process: Process = _
    private var shutdownHook: Thread = _

    def run(mainClass: String, classpath: Seq[File], options: Seq[String], log: Logger): Forked = synchronized {
      val javaOptions = config.runJVMOptions ++ Seq("-classpath", Path.makeString(classpath), mainClass) ++ options
      val strategy = config.outputStrategy getOrElse LoggedOutput(log)
      workingDirectory = if (temporary) Some(IO.createTemporaryDirectory) else config.workingDirectory
      shutdownHook = new Thread(new Runnable { def run(): Unit = destroy(log) })
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

    def stop(log: Logger): Unit = synchronized {
      cancelShutdownHook()
      destroy(log)
    }

    def destroy(log: Logger = null): Unit = synchronized {
      if (process ne null) {
        if (log ne null) log.info("Stopping " + name)
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
}