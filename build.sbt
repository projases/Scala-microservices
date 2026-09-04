import Dependencies._

ThisBuild / organization := "edu.uoc.epcsd"
ThisBuild / version      := "0.0.1-SNAPSHOT"
ThisBuild / scalaVersion := scala3

lazy val commonSettings = Seq(
  scalacOptions ++= Seq(
    "-deprecation",
    "-feature",
    "-unchecked",
    "-Wunused:all"
  ),
  Compile / run / fork := true,
  Test / parallelExecution := false,
  assembly / assemblyMergeStrategy := {
    case PathList("META-INF", "services", _ @ _*)             => MergeStrategy.concat
    case PathList("META-INF", "versions", _ @ _*)             => MergeStrategy.first
    case PathList("META-INF", xs @ _*)                        => MergeStrategy.discard
    case "module-info.class"                                  => MergeStrategy.discard
    case "application.conf"                                   => MergeStrategy.first
    case "logback.xml"                                        => MergeStrategy.first
    case x                                                    => MergeStrategy.first
  },
  assembly / assemblyJarName := s"${name.value}-assembly.jar"
)

lazy val root = (project in file("."))
  .settings(
    name := "scala-course",
    publish / skip := true
  )
  .aggregate(course, user, microcredential, productcatalog)

lazy val course = (project in file("modules/course"))
  .settings(
    name := "course",
    libraryDependencies ++= all,
    assembly / mainClass := Some("edu.uoc.epcsd.course.Main")
  )
  .settings(commonSettings)

lazy val user = (project in file("modules/user"))
  .settings(
    name := "user",
    libraryDependencies ++= (server ++ httpClient),
    assembly / mainClass := Some("edu.uoc.epcsd.user.Main")
  )
  .settings(commonSettings)

lazy val microcredential = (project in file("modules/microcredential"))
  .settings(
    name := "microcredential",
    libraryDependencies ++= (server ++ httpClient ++ rabbit),
    assembly / mainClass := Some("edu.uoc.epcsd.microcredential.Main")
  )
  .settings(commonSettings)

// Minimal in-memory product catalog stub, so the user service's POST /alerts product validation
// (GET /products/{id}) can be exercised end-to-end without a real catalog database.
lazy val productcatalog = (project in file("modules/productcatalog"))
  .settings(
    name := "productcatalog",
    libraryDependencies ++= Seq(
      catsEffect,
      http4sEmberServer,
      http4sDsl,
      logbackClassic
    ),
    assembly / mainClass := Some("edu.uoc.epcsd.productcatalog.Main")
  )
  .settings(commonSettings)
