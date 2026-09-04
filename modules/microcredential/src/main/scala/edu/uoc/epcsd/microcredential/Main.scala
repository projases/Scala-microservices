package edu.uoc.epcsd.microcredential

import cats.data.NonEmptyList
import cats.effect.{IO, IOApp, Resource}
import cats.effect.kernel.Clock
import cats.syntax.all.*
import com.comcast.ip4s.{Host, Port}
import dev.profunktor.fs2rabbit.config.{Fs2RabbitConfig, Fs2RabbitNodeConfig}
import dev.profunktor.fs2rabbit.interpreter.RabbitClient
import dev.profunktor.fs2rabbit.model.{ExchangeName, RoutingKey}
import doobie.hikari.HikariTransactor
import doobie.util.ExecutionContexts
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Router
import org.flywaydb.core.Flyway
import scala.concurrent.duration.*

import config.{AppConfig, RabbitConfig}
import domain.MicrocredentialEventPublisher
import infrastructure.repository.DoobieMicrocredentialRepository
import infrastructure.course.CourseClient
import infrastructure.events.RabbitMicrocredentialEventPublisher
import service.MicrocredentialService
import http.{ApiDocs, Routes}

object Main extends IOApp.Simple:

  def run: IO[Unit] =
    for
      cfg <- AppConfig.load.fold(e => IO.raiseError[AppConfig](new RuntimeException(e)), IO.pure)
      _   <- IO.whenA(cfg.database.migrate)(migrate(cfg))
      _   <- server(cfg).useForever
    yield ()

  private def migrate(cfg: AppConfig): IO[Unit] =
    IO {
      Flyway.configure()
        .dataSource(cfg.database.url, cfg.database.user, cfg.database.password)
        .load()
        .migrate()
    }.void

  private def server(cfg: AppConfig): Resource[IO, Unit] =
    for
      xa       <- transactor(cfg)
      client   <- EmberClientBuilder.default[IO].build
      repo      = new DoobieMicrocredentialRepository[IO](xa)
      courseSvc = new CourseClient[IO](client, cfg.courseService.baseUrl)
      publisher <- microcredentialEventPublisher(cfg)
      svc       = new MicrocredentialService[IO](repo, courseSvc, publisher, Clock[IO])
      api       = Routes[IO](svc) <+> ApiDocs.routes[IO]
      app       = Router("/" -> api).orNotFound
      _ <- EmberServerBuilder
             .default[IO]
             .withHost(Host.fromString(cfg.server.host).get)
             .withPort(Port.fromInt(cfg.server.port).get)
             .withHttpApp(app)
             .build
    yield ()

  /** Resolves the RabbitMQ-backed publisher, or a no-op when disabled. Lives only while the server
    *  runs; its background drain fiber is cancelled on shutdown.
    */
  private def microcredentialEventPublisher(cfg: AppConfig): Resource[IO, MicrocredentialEventPublisher[IO]] =
    val r = cfg.rabbit
    if r.enabled then
      RabbitClient.default[IO](toFs2RabbitConfig(r)).resource.flatMap { client =>
        RabbitMicrocredentialEventPublisher[IO](client, ExchangeName(r.exchange), RoutingKey(r.routingKey), cfg.retry)
      }
    else Resource.pure(MicrocredentialEventPublisher.Noop[IO])

  private def toFs2RabbitConfig(r: RabbitConfig): Fs2RabbitConfig =
    Fs2RabbitConfig(
      virtualHost = r.virtualHost,
      nodes = NonEmptyList.one(Fs2RabbitNodeConfig(host = r.host, port = r.port)),
      username = Some(r.username),
      password = Some(r.password),
      ssl = false,
      connectionTimeout = 3.seconds,
      requeueOnNack = false,
      requeueOnReject = false,
      internalQueueSize = Some(500),
      requestedHeartbeat = 30.seconds,
      automaticRecovery = true,
      automaticTopologyRecovery = true,
      clientProvidedConnectionName = Some("microcredential-service")
    )

  private def transactor(cfg: AppConfig): Resource[IO, HikariTransactor[IO]] =
    for
      ce <- ExecutionContexts.fixedThreadPool[IO](8)
      xa <- HikariTransactor.newHikariTransactor[IO](
              cfg.database.driver, cfg.database.url, cfg.database.user, cfg.database.password, ce
            )
    yield xa