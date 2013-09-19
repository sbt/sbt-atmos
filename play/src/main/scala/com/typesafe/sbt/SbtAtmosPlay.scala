/**
 *  Copyright (C) 2013 Typesafe, Inc <http://typesafe.com>
 */
package com.typesafe.sbt

import sbt._
import sbt.Keys._
import play.Project.playVersion

object SbtAtmosPlay extends Plugin {
  import SbtAtmos._
  import SbtAtmos.AtmosKeys._
  import atmos.AtmosPlayRun._
  import atmos.AtmosRun.AtmosTraceCompile

  lazy val atmosPlaySettings: Seq[Setting[_]] = atmosCompileSettings ++ inConfig(Atmos)(tracePlaySettings) ++ atmosPlayRunSettings

  def tracePlaySettings(): Seq[Setting[_]] = Seq(
    tracePlayVersion <<= playVersion map supportedPlayVersion,
    traceDependencies <<= (libraryDependencies, tracePlayVersion, atmosVersion) map tracePlayDependencies
  )
}
