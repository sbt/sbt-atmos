/**
 *  Copyright (C) 2013 Typesafe, Inc <http://typesafe.com>
 */
package com.typesafe.sbt

import sbt._
import sbt.Keys._

object SbtAtmosPlay extends Plugin {
  import SbtAtmos._
  import atmos.AtmosRunner._

  val AtmosPlay = config("atmos-play").extend(Atmos)

  lazy val atmosPlaySettings: Seq[Setting[_]] = atmosSettings
}
