import sbt._
import sbt.Keys._
import net.virtualvoid.sbt.cross.CrossPlugin

object SbtAtmosBuild extends Build {
  val Version = "0.2.3"

  lazy val sbtAtmos = Project(
    id = "sbt-atmos",
    base = file("."),
    settings = defaultSettings ++ noPublishSettings,
    aggregate = Seq(sbtAtmosAkka)
  )

  lazy val sbtAtmosAkka = Project(
    id = "sbt-atmos-akka",
    base = file("akka"),
    settings = defaultSettings ++ Seq(
      name := "sbt-atmos",
      libraryDependencies += Dependency.aspectjTools
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
  }
}
