
import sbt._
import Keys._

object AkkaStompBuild extends Build {
  val Organization = "akka.sample"
  val Version      = "0.1-SNAPSHOT"
  val ScalaVersion = "2.10.0-RC3"

  lazy val StompServer = Project(
    id = "akka-stomp-server",
    base = file("."),
    settings = defaultSettings ++ Seq(
      libraryDependencies ++= Dependencies.stompServer
    )
  )

  lazy val buildSettings = Defaults.defaultSettings ++ Seq(
    organization := Organization,
    version      := Version,
    scalaVersion := ScalaVersion,
    crossPaths   := false
  )
  
  lazy val defaultSettings = buildSettings ++ Seq(
    // compile options
    scalacOptions ++= Seq("-encoding", "UTF-8", "-deprecation", "-unchecked"),
    javacOptions  ++= Seq("-Xlint:unchecked", "-Xlint:deprecation")

  )
}

object Dependencies {
  import Dependency._

  val stompServer = Seq(akkaActor)
}

object Dependency {
  // Versions
  object V {
    val Akka      = "2.1.0-RC3"
  }

  val akkaActor = "com.typesafe.akka" %% "akka-actor" % V.Akka cross CrossVersion.full
}
