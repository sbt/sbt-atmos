import sbt._
import sbt.Keys._
import play.Project._
import com.typesafe.sbt.SbtAtmosPlay.atmosPlaySettings

object ApplicationBuild extends Build {
  val appName    = "traceplay"
  val appVersion = "1.0"

  val main = play.Project(appName, appVersion).settings(atmosPlaySettings: _*)
}
