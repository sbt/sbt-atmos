
sbtPlugin := true

organization := "com.typesafe.sbt"

name := "sbt-atmos"

version := "0.2.0-SNAPSHOT"

libraryDependencies += "org.aspectj" % "aspectjtools" % "1.7.2"

publishMavenStyle := false

publishTo <<= isSnapshot { snapshot =>
  if (snapshot) Some(Classpaths.sbtPluginSnapshots) else Some(Classpaths.sbtPluginReleases)
}

crossBuildingSettings

CrossBuilding.crossSbtVersions := Seq("0.12", "0.13")

CrossBuilding.scriptedSettings
