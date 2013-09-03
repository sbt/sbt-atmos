/**
 *  Copyright (C) 2013 Typesafe, Inc <http://typesafe.com>
 */
package com.typesafe.sbt

import sbt._
import sbt.Keys._
import java.net.URI

object SbtAtmos extends Plugin {
  import atmos.AtmosRunner._

  val AtmosVersion = "1.3.0-RC1"

  val Atmos = config("atmos").extend(Compile)
  val AtmosTest = config("atmos-test").extend(Atmos, Test)

  object AtmosKeys {
    val atmosVersion = SettingKey[String]("atmos-version")
    val aspectjVersion = SettingKey[String]("aspectj-version")
    val atmosDirectory = SettingKey[File]("atmos-directory")

    val traceOnly = TaskKey[Boolean]("trace-only")

    val atmosPort = TaskKey[Int]("atmos-port")
    val consolePort = TaskKey[Int]("console-port")
    val tracePort = TaskKey[Int]("trace-port")

    val atmosJvmOptions = TaskKey[Seq[String]]("atmos-jvm-options")
    val atmosConfigDirectory = SettingKey[File]("atmos-config-directory")
    val atmosLogDirectory = SettingKey[File]("atmos-log-directory")
    val atmosConfigString = TaskKey[String]("atmos-config-string")
    val atmosLogbackString = SettingKey[String]("atmos-logback-string")
    val atmosConfig = TaskKey[File]("atmos-config")
    val atmosConfigClasspath = TaskKey[Classpath]("atmos-config-classpath")
    val atmosManagedClasspath = TaskKey[Classpath]("atmos-managed-classpath")
    val atmosClasspath = TaskKey[Classpath]("atmos-classpath")

    val consoleJvmOptions = TaskKey[Seq[String]]("console-jvm-options")
    val consoleConfigString = TaskKey[String]("console-config-string")
    val consoleLogbackString = SettingKey[String]("console-logback-string")
    val consoleConfig = TaskKey[File]("console-config")
    val consoleConfigClasspath = TaskKey[Classpath]("console-config-classpath")
    val consoleManagedClasspath = TaskKey[Classpath]("console-managed-classpath")
    val consoleClasspath = TaskKey[Classpath]("console-classpath")

    val traceable = SettingKey[Seq[(String, Boolean)]]("traceable")
    val traceableConfigString = SettingKey[String]("traceable-config-string")
    val sampling = SettingKey[Seq[(String, Int)]]("sampling")
    val samplingConfigString = SettingKey[String]("sampling-config-string")
    val traceConfigString = TaskKey[String]("trace-config-string")
    val traceLogbackString = SettingKey[String]("trace-logback-string")
    val traceConfig = TaskKey[File]("trace-config")
    val traceConfigClasspath = TaskKey[Classpath]("trace-config-classpath")
    val aspectjWeaver = TaskKey[Option[File]]("aspectj-weaver")
    val sigarDependency = TaskKey[Option[File]]("sigar-dependency")
    val sigarLibs = TaskKey[Option[File]]("sigar-libs")
    val sigar = TaskKey[Sigar]("sigar")
    val traceOptions = TaskKey[Seq[String]]("trace-options")

    val atmosOptions = TaskKey[AtmosOptions]("atmos-options")
    val consoleOptions = TaskKey[AtmosOptions]("console-options")
    val atmosRunListeners = TaskKey[Seq[URI => Unit]]("atmos-run-listeners")
    val atmosInputs = TaskKey[AtmosInputs]("atmos-inputs")
  }

  import AtmosKeys._

  lazy val atmosSettings: Seq[Setting[_]] =
    inConfig(Atmos)(atmosScopedSettings(Compile, AtmosTraceCompile)) ++
    inConfig(AtmosTest)(atmosScopedSettings(Test, AtmosTraceTest)) ++
    atmosUnscopedSettings

  def atmosScopedSettings(extendConfig: Configuration, classpathConfig: Configuration): Seq[Setting[_]] =
    atmosConfigurationSettings(extendConfig, classpathConfig) ++
    atmosRunSettings(extendConfig)

  def atmosConfigurationSettings(extendConfig: Configuration, classpathConfig: Configuration): Seq[Setting[_]] = Seq(
    atmosVersion := AtmosVersion,
    aspectjVersion := "1.7.2",

    atmosDirectory <<= target / targetName(extendConfig),

    traceOnly := false,

    atmosPort := selectPort(8660),
    consolePort := selectPort(9900),
    tracePort := selectPort(28660),

    atmosJvmOptions := Seq("-Xms512m", "-Xmx512m"),
    atmosConfigDirectory <<= atmosDirectory / "conf",
    atmosLogDirectory <<= atmosDirectory / "log",
    atmosConfigString <<= tracePort map defaultAtmosConfig,
    atmosLogbackString <<= defaultLogbackConfig("atmos"),
    atmosConfig <<= writeConfig("atmos", atmosConfigString, atmosLogbackString),
    atmosConfigClasspath <<= atmosConfig map createClasspath,
    atmosManagedClasspath <<= collectManagedClasspath(AtmosDev),
    atmosClasspath <<= Classpaths.concat(atmosConfigClasspath, atmosManagedClasspath),

    consoleJvmOptions := Seq("-Xms512m", "-Xmx512m"),
    consoleConfigString <<= (name, atmosPort) map defaultConsoleConfig,
    consoleLogbackString <<= defaultLogbackConfig("console"),
    consoleConfig <<= writeConfig("console", consoleConfigString, consoleLogbackString),
    consoleConfigClasspath <<= consoleConfig map createClasspath,
    consoleManagedClasspath <<= collectManagedClasspath(AtmosConsole),
    consoleClasspath <<= Classpaths.concat(consoleConfigClasspath, consoleManagedClasspath),

    traceable := Seq("*" -> true),
    traceableConfigString <<= traceable apply { s => seqToConfig(s, indent = 6, quote = true) },
    sampling := Seq("*" -> 1),
    samplingConfigString <<= sampling apply { s => seqToConfig(s, indent = 6, quote = true) },
    traceConfigString <<= (normalizedName, traceableConfigString, samplingConfigString, tracePort) map defaultTraceConfig,
    traceLogbackString := "",
    traceConfig <<= writeConfig("trace", traceConfigString, traceLogbackString),
    traceConfigClasspath <<= traceConfig map createClasspath,
    aspectjWeaver <<= findAspectjWeaver,
    sigarDependency <<= findSigar,
    sigarLibs <<= unpackSigar,
    sigar <<= (sigarDependency, sigarLibs) map Sigar,
    traceOptions <<= (aspectjWeaver, sigarLibs) map traceJavaOptions,

    unmanagedClasspath <<= unmanagedClasspath in extendConfig,
    managedClasspath <<= collectManagedClasspath(classpathConfig),
    managedClasspath <<= Classpaths.concat(managedClasspath, traceConfigClasspath),
    internalDependencyClasspath <<= internalDependencyClasspath in extendConfig,
    externalDependencyClasspath <<= Classpaths.concat(unmanagedClasspath, managedClasspath),
    dependencyClasspath <<= Classpaths.concat(internalDependencyClasspath, externalDependencyClasspath),
    exportedProducts <<= exportedProducts in extendConfig,
    fullClasspath <<= Classpaths.concatDistinct(exportedProducts, dependencyClasspath),

    atmosOptions <<= (atmosPort, atmosJvmOptions, atmosClasspath) map AtmosOptions,
    consoleOptions <<= (consolePort, consoleJvmOptions, consoleClasspath) map AtmosOptions,
    atmosRunListeners := Seq.empty,
    atmosRunListeners <+= state map { s => logConsoleUri(s.log)(_) },
    atmosInputs <<= (traceOnly, atmosOptions, consoleOptions, atmosRunListeners) map AtmosInputs
  )

  def atmosRunSettings(extendConfig: Configuration): Seq[Setting[_]] = Seq(
    mainClass in run <<= mainClass in run in extendConfig,
    inTask(run)(Seq(runner <<= atmosRunner)).head,
    run <<= Defaults.runTask(fullClasspath, mainClass in run, runner in run),
    runMain <<= Defaults.runMainTask(fullClasspath, runner in run)
  )

  def atmosUnscopedSettings: Seq[Setting[_]] = Seq(
    ivyConfigurations ++= Seq(AtmosTraceCompile, AtmosTraceTest, AtmosDev, AtmosConsole, AtmosWeave, AtmosSigar),

    resolvers += "Typesafe Releases" at "http://repo.typesafe.com/typesafe/releases",

    libraryDependencies <++= (atmosVersion in Atmos)(atmosDependencies),
    libraryDependencies <++= (atmosVersion in Atmos)(consoleDependencies),
    libraryDependencies <++= (aspectjVersion in Atmos)(weaveDependencies),
    libraryDependencies <++= (atmosVersion in Atmos)(sigarDependencies),
    autoTraceAkkaDependencies
  )

  def autoTraceAkkaDependencies = {
    libraryDependencies <++= (libraryDependencies, atmosVersion in Atmos, scalaVersion)(selectTraceDependencies)
  }

  def traceAkka(akkaVersion: String) = {
    libraryDependencies <++= (atmosVersion in Atmos, scalaVersion)(traceDependencies(akkaVersion))
  }
}
