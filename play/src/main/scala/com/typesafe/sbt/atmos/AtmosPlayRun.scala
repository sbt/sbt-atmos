/**
 *  Copyright (C) 2013 Typesafe, Inc <http://typesafe.com>
 */
package com.typesafe.sbt
package atmos

import sbt._
import sbt.Keys._
import sbt.PlayKeys.playRunHooks
import play.Project.ClassLoaderCreator
import org.aspectj.weaver.loadtime.WeavingURLClassLoader

object AtmosPlayRun {
  import AtmosRunner._
  import SbtAtmos.AtmosKeys._
  import SbtAtmosPlay.AtmosPlay
  import SbtAtmosPlay.AtmosPlayKeys.weavingClassLoader

  def atmosPlayRunSettings(): Seq[Setting[_]] = Seq(
    weavingClassLoader in AtmosPlay <<= (sigar in AtmosPlay) map createWeavingClassLoader,
    playRunHooks in AtmosPlay <<= playRunHooks,
    playRunHooks in AtmosPlay <+= (javaHome in run in AtmosPlay, atmosInputs in AtmosPlay, sigarLibs in AtmosPlay, state) map { (javaHome, inputs, sigar, s) =>
      new RunHook(javaHome, inputs, sigar, s.log)
    }
  ) ++ AtmosPlaySpecific.atmosPlaySpecificSettings

  def createWeavingClassLoader(sigar: Sigar): ClassLoaderCreator = (name, urls, parent) => new WeavingURLClassLoader(urls, parent) {
    val sigarLoader = SigarClassLoader(sigar)
    override def loadClass(name: String, resolve: Boolean): Class[_] = {
      if (name startsWith "org.hyperic.sigar") sigarLoader.loadClass(name)
      else super.loadClass(name, resolve)
    }
    override def toString = "Weaving" + name + "{" + getURLs.map(_.toString).mkString(", ") + "}"
  }

  class RunHook(javaHome: Option[File], inputs: AtmosInputs, sigarLibs: Option[File], log: Logger) extends AtmosController(javaHome, inputs, log) with play.PlayRunHook {
    override def beforeStarted(): Unit = {
      System.setProperty("org.aspectj.tracing.factory", "default")
      System.setProperty("config.resource", "application.conf")
      sigarLibs foreach { s => System.setProperty("org.hyperic.sigar.path", s.getAbsolutePath) }
      start()
    }
    override def afterStopped(): Unit = stop()
  }
}
