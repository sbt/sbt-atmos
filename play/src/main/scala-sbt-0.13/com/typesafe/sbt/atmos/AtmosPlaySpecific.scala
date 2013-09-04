/**
 *  Copyright (C) 2013 Typesafe, Inc <http://typesafe.com>
 */
package com.typesafe.sbt
package atmos

import sbt._
import sbt.Keys._
import play.Keys.playRunHooks
import play.Project.{ playRunTask, playReloaderClasspath, playReloaderClassLoader }

object AtmosPlaySpecific {
	import SbtAtmos.Atmos
	import SbtAtmosPlay.AtmosPlay
  import SbtAtmosPlay.AtmosPlayKeys.weavingClassLoader

  def atmosPlaySpecificSettings(): Seq[Setting[_]] = Seq(
    playRunHooks in AtmosPlay <<= playRunHooks,
    playRunHooks in AtmosPlay <+= AtmosPlayRun.createRunHook,
    run in AtmosPlay <<= playRunTask(playRunHooks in AtmosPlay, externalDependencyClasspath in AtmosPlay, weavingClassLoader in AtmosPlay, playReloaderClasspath, playReloaderClassLoader),
    run in Atmos <<= run in AtmosPlay
  )
}
