import sbt._
import sbt.Keys._
import net.virtualvoid.sbt.cross.CrossPlugin

object SbtAtmosBuild extends Build {
  val Version = "0.2.0-SNAPSHOT"

  lazy val sbtAtmos = Project(
    id = "sbt-atmos",
    base = file("."),
    settings = defaultSettings ++ noPublishSettings,
    aggregate = Seq(sbtAtmosAkka, sbtAtmosPlay)
  )

  lazy val sbtAtmosAkka = Project(
    id = "sbt-atmos-akka",
    base = file("akka"),
    settings = defaultSettings ++ Seq(
      name := "sbt-atmos",
      libraryDependencies += Dependency.aspectjTools
    )
  )

  lazy val sbtAtmosPlay = Project(
    id = "sbt-atmos-play",
    base = file("play"),
    dependencies = Seq(sbtAtmosAkka),
    settings = defaultSettings ++ Seq(
      name := "sbt-atmos-play",
      libraryDependencies <+= Dependency.playPlugin
    )
  )

  lazy val defaultSettings: Seq[Setting[_]] = Defaults.defaultSettings ++ crossBuildSettings ++ Seq(
    sbtPlugin := true,
    organization := "com.typesafe.sbt",
    version := Version,
    publishMavenStyle := false,
    publishTo <<= isSnapshot { snapshot =>
      if (snapshot) Some(Classpaths.sbtPluginSnapshots) else Some(Classpaths.sbtPluginReleases)
    }
  )

  lazy val crossBuildSettings: Seq[Setting[_]] = CrossPlugin.crossBuildingSettings ++ CrossBuilding.scriptedSettings ++ Seq(
    CrossBuilding.crossSbtVersions := Seq("0.12", "0.13")
  )

  lazy val noPublishSettings: Seq[Setting[_]] = Seq(
    publish := {},
    publishLocal := {}
  )

  object Dependency {
    val aspectjTools = "org.aspectj" % "aspectjtools" % "1.7.2"

    def playPlugin = (sbtVersion in sbtPlugin, scalaBinaryVersion in update) { (sbtV, scalaV) =>
      val dependency = sbtV match {
        case "0.12" => "play" % "sbt-plugin" % "2.1-SNAPSHOT"
        case "0.13" => "com.typesafe.play" % "sbt-plugin" % "2.2-SNAPSHOT"
        case _ => sys.error("Unsupported sbt version: " + sbtV)
      }
      Defaults.sbtPluginExtra(dependency, sbtV, scalaV)
    }
  }
}
