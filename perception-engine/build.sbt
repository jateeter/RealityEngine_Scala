val AkkaVersion     = "2.8.6"
val AkkaHttpVersion = "10.5.3"
val CirceVersion    = "0.14.7"
val SttpVersion     = "3.9.7"

ThisBuild / scalaVersion := "2.13.18"
ThisBuild / organization := "com.realityengine"

lazy val root = (project in file("."))
  .settings(
    name    := "perception-engine",
    version := "1.0.0",

    libraryDependencies ++= Seq(
      // Akka HTTP + Streams
      "com.typesafe.akka" %% "akka-actor-typed"        % AkkaVersion,
      "com.typesafe.akka" %% "akka-stream"             % AkkaVersion,
      "com.typesafe.akka" %% "akka-http"               % AkkaHttpVersion,
      // circe JSON
      "io.circe" %% "circe-core"    % CirceVersion,
      "io.circe" %% "circe-generic" % CirceVersion,
      "io.circe" %% "circe-parser"  % CirceVersion,
      "de.heikoseeberger" %% "akka-http-circe" % "1.39.2",

      // sttp (sync backend for outbound HTTP)
      "com.softwaremill.sttp.client3" %% "core" % SttpVersion,

      // Logging
      "ch.qos.logback" % "logback-classic" % "1.4.14",

      // UUID
      "com.fasterxml.uuid" % "java-uuid-generator" % "4.3.0",

      // MQTT — Eclipse Paho Java client
      "org.eclipse.paho" % "org.eclipse.paho.client.mqttv3" % "1.2.5",
    ),

    assembly / mainClass        := Some("com.realityengine.perception.PerceptionMain"),
    assembly / assemblyJarName  := "perception-engine.jar",
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "MANIFEST.MF")                   => MergeStrategy.discard
      case PathList("META-INF", "services", _*)                  => MergeStrategy.concat
      case PathList("reference.conf")                            => MergeStrategy.concat
      case PathList("META-INF", "io.netty.versions.properties")  => MergeStrategy.first
      case PathList("module-info.class")                         => MergeStrategy.discard
      case _                                                     => MergeStrategy.first
    }
  )
