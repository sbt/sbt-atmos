/**
 *  Copyright (C) 2013 Typesafe, Inc <http://typesafe.com>
 */
package com.typesafe.sbt
package atmos

import sbt._
import sbt.Keys._
import sbt.PlayKeys.playRunHooks
import play.Project.{ playRunTask, playReloaderClasspath, playReloaderClassLoader }

object AtmosPlaySpecific {
	import SbtAtmosPlay.AtmosPlay
  import SbtAtmosPlay.AtmosPlayKeys.weavingClassLoader

  def atmosPlaySpecificSettings(): Seq[Setting[_]] = Seq(
    run in AtmosPlay <<= playRunTask(playRunHooks in AtmosPlay, externalDependencyClasspath in AtmosPlay, weavingClassLoader in AtmosPlay, playReloaderClasspath, playReloaderClassLoader)
  ) ++ SbtAtmos.traceAkka("2.2.0")
}
