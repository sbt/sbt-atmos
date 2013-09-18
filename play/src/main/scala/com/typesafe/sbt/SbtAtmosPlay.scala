/**
 *  Copyright (C) 2013 Typesafe, Inc <http://typesafe.com>
 */
package com.typesafe.sbt

import sbt._
import sbt.Keys._
import play.Project.playVersion

object SbtAtmosPlay extends Plugin {
  import SbtAtmos._
  import atmos.AtmosPlayRun._
  import atmos.AtmosRun.AtmosTraceCompile
  import AtmosKeys._

  lazy val atmosPlaySettings: Seq[Setting[_]] = atmosCompileSettings ++ atmosPlayRunSettings ++ tracePlay

  def tracePlay(): Seq[Setting[_]] = Seq(
    libraryDependencies <++= (playVersion, atmosVersion in Atmos)(tracePlayDependencies)
  )
}
