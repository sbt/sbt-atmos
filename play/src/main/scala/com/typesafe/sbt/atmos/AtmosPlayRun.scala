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

  val Play21Version = "2.1.4"
  val Play22Version = "2.2.0"

  def atmosPlayRunSettings(): Seq[Setting[_]] = Seq(
    weavingClassLoader in Atmos <<= (sigar in Atmos) map createWeavingClassLoader
  ) ++ AtmosPlaySpecific.atmosPlaySpecificSettings

  def tracePlayDependencies(dependencies: Seq[ModuleID], playVersion: String, atmosVersion: String): Seq[ModuleID] = {
    if (containsTracePlay(dependencies)) Seq.empty[ModuleID]
    else Seq("com.typesafe.atmos" % ("trace-play-" + playVersion) % atmosVersion % AtmosTraceCompile.name cross CrossVersion.Disabled)
  }

  def supportedPlayVersion(playVersion: String): String = {
    if      (playVersion startsWith "2.1.") Play21Version
    else if (playVersion startsWith "2.2.") Play22Version
    else    sys.error("Play version is not supported by Typesafe Console: " + playVersion)
  }

  def containsTracePlay(dependencies: Seq[ModuleID]): Boolean = dependencies exists { module =>
    module.organization == "com.typesafe.atmos" && module.name.startsWith("trace-play")
  }

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
      sys.props.getOrElseUpdate("config.resource", "application.conf")
      sigarLibs foreach { s => System.setProperty("org.hyperic.sigar.path", s.getAbsolutePath) }
      AtmosController.start(inputs, log)
    }
    override def afterStopped(): Unit = AtmosController.stop(log)
  }
}
