package edu.uoc.epcsd.productcatalog

import cats.data.NonEmptyList
import cats.effect.{IO, IOApp, Resource}
import dev.profunktor.fs2rabbit.config.{Fs2RabbitConfig, Fs2RabbitNodeConfig}
import dev.profunktor.fs2rabbit.interpreter.RabbitClient
import dev.profunktor.fs2rabbit.model.{ExchangeName, RoutingKey}
import org.http4s.HttpRoutes
import org.http4s.dsl.io.*
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits.*
import com.comcast.ip4s.{Host, Port}
import scala.concurrent.duration.*

import config.{AppConfig, RabbitConfig}
import infrastructure.events.{ProductEventPublisher, RabbitProductEventPublisher}

/** Minimal in-memory product catalog stub.
  *
  *  The user service validates new alerts against the catalog by calling
  *  `GET /products/{productId}` and treating 200 as "exists" and 404 as "does not exist"
  *  (see `ProductClient.existsById`). This stub reproduces that contract so `POST /alerts`
  *  can be exercised end-to-end in the portfolio demo without a real catalog database.
  *
  *  It also publishes `product.unit_available` events (RabbitMQ, exchange `product.events`)
  *  when units are added to a product via `POST /products/{productId}/units`, feeding the
  *  notification service's product leg.
  */
object Main extends IOApp.Simple:

  /** ids considered to exist in the catalog */
  private val existingProductIds: Set[Long] = Set(1L, 2L, 3L, 10L, 11L, 12L, 20L, 100L, 101L, 102L)

  private def routes(publisher: ProductEventPublisher[IO]): HttpRoutes[IO] =
    HttpRoutes.of[IO] {
      case GET -> Root / "products" / LongVar(id) =>
        if existingProductIds.contains(id) then Ok(s"""{"id":$id,"name":"Stub Product $id"}""")
        else NotFound(s"""{"message":"Product $id not found"}""")

      case POST -> Root / "products" / LongVar(id) / "units" =>
        if existingProductIds.contains(id) then
          publisher.publishUnitAvailable(id) *> Ok(s"""{"productId":$id,"unitsAdded":true}""")
        else NotFound(s"""{"message":"Product $id not found"}""")
    }

  def run: IO[Unit] =
    for
      cfg <- AppConfig.load.fold(e => IO.raiseError[AppConfig](new RuntimeException(e)), IO.pure)
      _   <- serve(cfg).useForever
    yield ()

  private def serve(cfg: AppConfig): Resource[IO, Unit] =
    for
      publisher <- productPublisher(cfg)
      myRoutes   = routes(publisher)
      _ <- EmberServerBuilder
             .default[IO]
             .withHost(Host.fromString(cfg.server.host).get)
             .withPort(Port.fromInt(cfg.server.port).get)
             .withHttpApp(myRoutes.orNotFound)
             .build
    yield ()

  private def productPublisher(cfg: AppConfig): Resource[IO, ProductEventPublisher[IO]] =
    val r = cfg.rabbit
    if r.enabled then
      RabbitClient.default[IO](toFs2RabbitConfig(r)).resource.flatMap { client =>
        RabbitProductEventPublisher[IO](
          client, ExchangeName("product.events"), RoutingKey("product.unit_available"), cfg.retry
        )
      }
    else Resource.pure(ProductEventPublisher.Noop[IO])

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
      clientProvidedConnectionName = Some("productcatalog-service")
    )
