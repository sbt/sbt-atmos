/**
 *  Copyright (C) 2013 Typesafe, Inc <http://typesafe.com>
 */
package com.typesafe.sbt

import sbt._
import sbt.Keys._
import play.Project.ClassLoaderCreator

object SbtAtmosPlay extends Plugin {
  import SbtAtmos._
  import atmos.AtmosPlayRun
  import atmos.AtmosRunner.AtmosTraceCompile

  val AtmosPlay = config("atmos-play").extend(Atmos)

  object AtmosPlayKeys {
    val weavingClassLoader = TaskKey[ClassLoaderCreator]("weaving-class-loader")
  }

  import AtmosPlayKeys._

  lazy val atmosPlaySettings: Seq[Setting[_]] =
    atmosSettings ++
    inConfig(AtmosPlay)(atmosConfigurationSettings(Compile, AtmosTraceCompile)) ++
    AtmosPlayRun.atmosPlayRunSettings
}
