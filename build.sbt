name         := "reality-engine"
version      := "1.0.0"
scalaVersion := "2.13.18"
organization := "com.realityengine"

val AkkaVersion     = "2.8.6"
val AkkaHttpVersion = "10.5.3"
val CirceVersion    = "0.14.7"
val SttpVersion     = "3.9.7"

libraryDependencies ++= Seq(
  // Akka HTTP
  "com.typesafe.akka"             %% "akka-http"              % AkkaHttpVersion,
  "com.typesafe.akka"             %% "akka-stream"            % AkkaVersion,
  "com.typesafe.akka"             %% "akka-slf4j"             % AkkaVersion,

  // Circe JSON + Akka HTTP integration
  "io.circe"                      %% "circe-core"             % CirceVersion,
  "io.circe"                      %% "circe-generic"          % CirceVersion,
  "io.circe"                      %% "circe-parser"           % CirceVersion,
  "de.heikoseeberger"             %% "akka-http-circe"        % "1.39.2",

  // sttp for Qdrant REST calls
  "com.softwaremill.sttp.client3" %% "core"                   % SttpVersion,
  "com.softwaremill.sttp.client3" %% "circe"                  % SttpVersion,
  "com.softwaremill.sttp.client3" %% "akka-http-backend"      % SttpVersion,

  // Logging
  "ch.qos.logback"                %  "logback-classic"        % "1.5.6",

  // Test
  "org.scalatest"                 %% "scalatest"              % "3.2.18"       % Test,
  "com.typesafe.akka"             %% "akka-http-testkit"      % AkkaHttpVersion % Test,
  "com.typesafe.akka"             %% "akka-stream-testkit"    % AkkaVersion    % Test
)

// Fat-jar assembly
assembly / assemblyJarName := "reality-engine.jar"
assembly / mainClass       := Some("com.realityengine.Main")
Compile / mainClass        := Some("com.realityengine.Main")

assembly / assemblyMergeStrategy := {
  case PathList("META-INF", "MANIFEST.MF")       => MergeStrategy.discard
  case PathList("META-INF", "services", _*)       => MergeStrategy.concat
  case PathList("META-INF", xs @ _*)              => MergeStrategy.discard
  case "reference.conf"                           => MergeStrategy.concat
  case "application.conf"                         => MergeStrategy.concat
  case _                                          => MergeStrategy.first
}

scalacOptions ++= Seq(
  "-encoding", "utf8",
  "-deprecation",
  "-feature",
  "-unchecked",
  "-Xlint:unused"
)
