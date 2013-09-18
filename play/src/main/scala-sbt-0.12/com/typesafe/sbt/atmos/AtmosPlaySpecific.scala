/**
 *  Copyright (C) 2013 Typesafe, Inc <http://typesafe.com>
 */
package com.typesafe.sbt
package atmos

import sbt._
import sbt.Keys._
import sbt.PlayKeys.playRunHooks
import play.Project.{ createPlayRunCommand, playReloaderClasspath, playReloaderClassLoader }

object AtmosPlaySpecific {
  import SbtAtmos.Atmos
  import SbtAtmos.AtmosKeys._

  def atmosPlaySpecificSettings(): Seq[Setting[_]] = Seq(
    playRunHooks in Atmos <<= playRunHooks,
    playRunHooks in Atmos <+= AtmosPlayRun.createRunHook,
    commands += createPlayRunCommand("atmos-run", playRunHooks in Atmos, externalDependencyClasspath in Atmos, weavingClassLoader in Atmos, playReloaderClasspath, playReloaderClassLoader)
  )
}
