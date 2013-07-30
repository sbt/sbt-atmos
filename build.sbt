
sbtPlugin := true

organization := "com.typesafe.sbt"

name := "sbt-atmos"

version := "0.1.2-SNAPSHOT"

publishMavenStyle := false

publishTo <<= (version) { v =>
  def scalasbt(repo: String) = ("scalasbt " + repo, "http://scalasbt.artifactoryonline.com/scalasbt/sbt-plugin-" + repo)
  val (name, repo) = if (v.endsWith("-SNAPSHOT")) scalasbt("snapshots") else scalasbt("releases")
  Some(Resolver.url(name, url(repo))(Resolver.ivyStylePatterns))
}

crossBuildingSettings

CrossBuilding.crossSbtVersions := Seq("0.12", "0.13")

CrossBuilding.scriptedSettings
