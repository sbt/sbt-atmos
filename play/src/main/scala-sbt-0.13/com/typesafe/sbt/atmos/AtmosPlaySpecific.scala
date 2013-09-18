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
  import SbtAtmos.AtmosKeys._

  def atmosPlaySpecificSettings(): Seq[Setting[_]] = Seq(
    playRunHooks in Atmos <<= playRunHooks,
    playRunHooks in Atmos <+= AtmosPlayRun.createRunHook,
    run in Atmos <<= playRunTask(playRunHooks in Atmos, externalDependencyClasspath in Atmos, weavingClassLoader in Atmos, playReloaderClasspath, playReloaderClassLoader)
  )
}
