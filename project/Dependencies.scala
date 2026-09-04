import sbt._

object Dependencies {
  val scala3 = "3.3.8"

  private val catsEffectV = "3.5.7"
  private val catsV       = "2.12.0"
  private val http4sV     = "0.23.30"
  private val circeV      = "0.14.10"
  private val doobieV     = "1.0.0-RC5"
  private val fs2RabbitV  = "5.5.3"
  private val flywayV     = "11.3.3"
  private val munitV      = "1.1.0"
  private val munitCE3V   = "2.1.0"
  private val scalacheckV = "1.18.1"

  val catsCore   = "org.typelevel" %% "cats-core"   % catsV
  val catsEffect = "org.typelevel" %% "cats-effect" % catsEffectV

  val http4sEmberServer = "org.http4s" %% "http4s-ember-server" % http4sV
  val http4sEmberClient = "org.http4s" %% "http4s-ember-client" % http4sV
  val http4sCirce       = "org.http4s" %% "http4s-circe"       % http4sV
  val http4sDsl         = "org.http4s" %% "http4s-dsl"         % http4sV

  val circeCore    = "io.circe" %% "circe-core"    % circeV
  val circeGeneric = "io.circe" %% "circe-generic" % circeV
  val circeLiteral = "io.circe" %% "circe-literal" % circeV
  val circeParser  = "io.circe" %% "circe-parser"  % circeV

  val doobieCore     = "org.tpolecat" %% "doobie-core"     % doobieV
  val doobiePostgres = "org.tpolecat" %% "doobie-postgres" % doobieV
  val doobieHikari   = "org.tpolecat" %% "doobie-hikari"   % doobieV

  val fs2Rabbit = "dev.profunktor" %% "fs2-rabbit" % fs2RabbitV

  val flywayCore = "org.flywaydb" % "flyway-core" % flywayV

  val flywayPostgres = "org.flywaydb" % "flyway-database-postgresql" % flywayV

  val postgres = "org.postgresql" % "postgresql" % "42.7.4"

  val pureconfig = "com.github.pureconfig" %% "pureconfig-core" % "0.17.8"

  val log4catsSlf4j  = "org.typelevel" %% "log4cats-slf4j"  % "2.7.0"
  val logbackClassic = "ch.qos.logback" % "logback-classic" % "1.5.8"

  // Aligned with the project's circa-mid-2024 stack (cats-effect 3.5.x, http4s 0.23.30,
  // circe 0.14.10, Scala 3.3.8) so eviction keeps the dependency set stable.
  private val tapirV               = "1.10.15"
  private val tapirServer         = "com.softwaremill.sttp.tapir" %% "tapir-http4s-server" % tapirV
  private val tapirCirce          = "com.softwaremill.sttp.tapir" %% "tapir-json-circe"    % tapirV
  private val tapirOpenapiDocs    = "com.softwaremill.sttp.tapir" %% "tapir-openapi-docs"    % tapirV
  // Circe encoders for the OpenAPI v3 model (matches openapi-model 0.11.x pulled by tapir-openapi-docs).
  val openapiCirce = "com.softwaremill.sttp.apispec" %% "openapi-circe" % "0.11.0"

  val munit           = "org.scalameta"    %% "munit"             % munitV      % Test
  val munitCE3        = "org.typelevel"    %% "munit-cats-effect" % munitCE3V   % Test
  val scalacheck      = "org.scalacheck"   %% "scalacheck"        % scalacheckV % Test
  val munitScalaCheck = "org.scalameta"    %% "munit-scalacheck"  % munitV      % Test

  val all = Seq(
    catsCore,
    catsEffect,
    http4sEmberServer,
    http4sEmberClient,
    http4sCirce,
    http4sDsl,
    circeCore,
    circeGeneric,
    circeLiteral,
    circeParser,
    tapirServer,
    tapirCirce,
    tapirOpenapiDocs,
    openapiCirce,
    doobieCore,
    doobiePostgres,
    doobieHikari,
    fs2Rabbit,
    flywayCore,
    flywayPostgres,
    postgres,
    pureconfig,
    log4catsSlf4j,
    logbackClassic,
    munit,
    munitCE3,
    scalacheck,
    munitScalaCheck
  )

  /** Dependencies for modules that expose an HTTP API backed by Postgres (course, user, microcredential). */
  val server = Seq(
    catsCore,
    catsEffect,
    http4sEmberServer,
    http4sCirce,
    http4sDsl,
    circeCore,
    circeGeneric,
    circeParser,
    tapirServer,
    tapirCirce,
    tapirOpenapiDocs,
    openapiCirce,
    doobieCore,
    doobiePostgres,
    doobieHikari,
    flywayCore,
    flywayPostgres,
    postgres,
    pureconfig,
    log4catsSlf4j,
    logbackClassic,
    munit,
    munitCE3
  )

  /** HTTP client + RabbitMQ for modules that talk to other services and publish events. */
  val httpClient = Seq(http4sEmberClient, http4sCirce)
  val rabbit     = Seq(fs2Rabbit)
}
