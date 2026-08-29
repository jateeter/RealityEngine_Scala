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

// Suites share process-global singletons — SemanticAuditLog, ArbitrationRegistry
// — so running them in parallel is not safe, and the failure mode is silent.
// SemanticAuditLog is a 1000-entry ring buffer: CesgenOraclesParitySpec pushes
// 4966 machines through processInput, which evicts whatever SemanticAuditSpec
// had just recorded before it can read it back. That surfaced as "58 was not
// equal to 2" and then "0 was not equal to 2" from a spec that passes in
// isolation on any branch.
//
// Scoping the assertions to their own machine (which those specs now also do)
// is not sufficient on its own: eviction removes the records entirely, so
// there is nothing left to filter. Serialising is the fix that matches the
// cause.
Test / parallelExecution := false
