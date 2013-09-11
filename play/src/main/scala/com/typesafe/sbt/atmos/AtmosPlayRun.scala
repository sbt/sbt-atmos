/**
 *  Copyright (C) 2013 Typesafe, Inc <http://typesafe.com>
 */
package com.typesafe.sbt
package atmos

import sbt._
import sbt.Keys._
import play.Project.ClassLoaderCreator
import org.aspectj.weaver.loadtime.WeavingURLClassLoader

object AtmosPlayRun {
  import AtmosRun._
  import SbtAtmos.Atmos
  import SbtAtmos.AtmosKeys._
  import SbtAtmosPlay.AtmosPlayKeys.weavingClassLoader

  def atmosPlayRunSettings(): Seq[Setting[_]] = Seq(
    weavingClassLoader in Atmos <<= (sigar in Atmos) map createWeavingClassLoader
  ) ++ AtmosPlaySpecific.atmosPlaySpecificSettings

  def tracePlayDependencies(playVersion: String, atmosVersion: String): Seq[ModuleID] = Seq(
    "com.typesafe.atmos" % ("trace-play-" + playVersion) % atmosVersion % AtmosTraceCompile.name cross CrossVersion.Disabled
  )

  def createWeavingClassLoader(sigar: Sigar): ClassLoaderCreator = (name, urls, parent) => new WeavingURLClassLoader(urls, parent) {
    val sigarLoader = SigarClassLoader(sigar)
    override def loadClass(name: String, resolve: Boolean): Class[_] = {
      if (name startsWith "org.hyperic.sigar") sigarLoader.loadClass(name)
      else super.loadClass(name, resolve)
    }
    override def toString = "Weaving" + name + "{" + getURLs.map(_.toString).mkString(", ") + "}"
  }

  def createRunHook = (atmosInputs in Atmos, sigarLibs in Atmos, state) map { (inputs, sigar, s) =>
    new RunHook(inputs, sigar, s.log)
  }

  class RunHook(inputs: AtmosInputs, sigarLibs: Option[File], log: Logger) extends play.PlayRunHook {
    override def beforeStarted(): Unit = {
      System.setProperty("org.aspectj.tracing.factory", "default")
      System.setProperty("config.resource", "application.conf")
      sigarLibs foreach { s => System.setProperty("org.hyperic.sigar.path", s.getAbsolutePath) }
      AtmosController.start(inputs, log)
    }
    override def afterStopped(): Unit = AtmosController.stop(log)
  }
}
