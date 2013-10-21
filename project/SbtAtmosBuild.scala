import sbt._
import sbt.Keys._
import net.virtualvoid.sbt.cross.CrossPlugin

object SbtAtmosBuild extends Build {
  val Version = "0.3.3-SNAPSHOT"

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
      name := "sbt-atmos-play"
    ) ++ Dependency.playPlugin
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
    val aspectjTools = "org.aspectj" % "aspectjtools" % "1.7.3"

    def playPlugin: Seq[Setting[_]] = Seq(
      resolvers += Classpaths.typesafeSnapshots,
      resolvers += "Typesafe Maven Snapshots" at "http://repo.typesafe.com/typesafe/snapshots/",
      resolvers += "Typesafe Maven Releases" at "http://repo.typesafe.com/typesafe/releases/",
      libraryDependencies <+= (sbtVersion in sbtPlugin, scalaBinaryVersion in update) { (sbtV, scalaV) =>
        val dependency = sbtV match {
          case "0.12" => "play" % "sbt-plugin" % "2.1.4"
          case "0.13" => "com.typesafe.play" % "sbt-plugin" % "2.2.0"
          case _ => sys.error("Unsupported sbt version: " + sbtV)
        }
        Defaults.sbtPluginExtra(dependency, sbtV, scalaV)
      }
    )
  }
}
