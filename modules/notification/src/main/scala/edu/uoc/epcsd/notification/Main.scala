package edu.uoc.epcsd.notification

import cats.data.NonEmptyList
import cats.effect.{IO, IOApp, Resource}
import dev.profunktor.fs2rabbit.config.{Fs2RabbitConfig, Fs2RabbitNodeConfig}
import dev.profunktor.fs2rabbit.interpreter.RabbitClient
import org.http4s.ember.client.EmberClientBuilder
import scala.concurrent.duration.*

import config.AppConfig
import infrastructure.events.NotificationConsumer
import infrastructure.user.UserClient
import infrastructure.productcatalog.ProductClient
import service.NotificationService

object Main extends IOApp.Simple:

  def run: IO[Unit] =
    for
      cfg <- AppConfig.load.fold(e => IO.raiseError[AppConfig](new RuntimeException(e)), IO.pure)
      _   <- consume(cfg).useForever
    yield ()

  /** The notification service has no REST API or database of its own: it is a pure RabbitMQ
    *  consumer that turns asynchronous domain events into (logged) user emails.
    */
  private def consume(cfg: AppConfig): Resource[IO, Unit] =
    for
      client        <- EmberClientBuilder.default[IO].build
      userClient     = new UserClient[IO](client, cfg.userService.baseUrl)
      productClient  = new ProductClient[IO](client, cfg.productService.baseUrl)
      service        = NotificationService[IO](userClient, productClient)
      rclient       <- rabbitConnection(cfg)
      _             <- rclient match
                         case Some(r) => NotificationConsumer[IO](r, cfg.microcredential, cfg.product, service)
                         case None    => Resource.unit[IO]
    yield ()

  private def rabbitConnection(cfg: AppConfig): Resource[IO, Option[RabbitClient[IO]]] =
    val enabled = cfg.microcredential.enabled || cfg.product.enabled
    if enabled then
      RabbitClient.default[IO](toFs2RabbitConfig(cfg)).resource.map(Some(_))
    else Resource.pure(None)

  private def toFs2RabbitConfig(cfg: AppConfig): Fs2RabbitConfig =
    val r = cfg.microcredential
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
      clientProvidedConnectionName = Some("notification-service")
    )
