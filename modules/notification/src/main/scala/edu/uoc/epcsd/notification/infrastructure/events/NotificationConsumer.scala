package edu.uoc.epcsd.notification.infrastructure.events

import cats.effect.std.Supervisor
import cats.effect.{Async, Resource, Sync}
import cats.syntax.all.*
import dev.profunktor.fs2rabbit.config.declaration.DeclarationQueueConfig
import dev.profunktor.fs2rabbit.interpreter.RabbitClient
import dev.profunktor.fs2rabbit.model.*
import io.circe.Decoder
import io.circe.parser.decode
import fs2.Stream
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import edu.uoc.epcsd.notification.config.RabbitStreamConfig
import edu.uoc.epcsd.notification.service.NotificationService

/** Declarative, functional RabbitMQ consumers for the notification service.
  *
  *  One auto-ack consumer is opened per event stream (microcredential, product), each bound to its
  *  exchange on the configured routing keys. Each message is decoded to a typed domain event and
  *  dispatched to the `NotificationService` as a single `evalMap` — the whole leg is a pure fs2
  *  Stream, cancelled cleanly when the server resource finalises.
  */
object NotificationConsumer:

  def apply[F[_]: Async](
      client: RabbitClient[F],
      micro: RabbitStreamConfig,
      product: RabbitStreamConfig,
      service: NotificationService[F]
  ): Resource[F, Unit] =
    for
      _ <- if micro.enabled then microStream(client, micro, service) else Resource.unit[F]
      _ <- if product.enabled then productStream(client, product, service) else Resource.unit[F]
    yield ()

  private def microStream[F[_]: Async](
      client: RabbitClient[F],
      cfg: RabbitStreamConfig,
      service: NotificationService[F]
  ): Resource[F, Unit] =
    stream(client, cfg) { (consumer, logger) =>
      consumer.evalMap { env =>
        if env.routingKey.value.endsWith(".pending") then
          decodeThen(env, logger, service.notifyCredentialPending)
        else if env.routingKey.value.endsWith(".approved") then
          decodeThen(env, logger, service.notifyCredentialGranted)
        else
          decodeThen(env, logger, service.notifyCredentialRejected)
      }
    }

  private def productStream[F[_]: Async](
      client: RabbitClient[F],
      cfg: RabbitStreamConfig,
      service: NotificationService[F]
  ): Resource[F, Unit] =
    stream(client, cfg) { (consumer, logger) =>
      consumer.evalMap { env =>
        decodeThen(env, logger, service.notifyProductAvailable)
      }
    }

  private def stream[F[_]: Async](
      client: RabbitClient[F],
      cfg: RabbitStreamConfig
  )(compile: (Stream[F, AmqpEnvelope[String]], Logger[F]) => Stream[F, Unit]): Resource[F, Unit] =
    client.createConnectionChannel.flatMap { implicit channel =>
      val acquire: F[(Stream[F, AmqpEnvelope[String]], Logger[F])] =
        for
          _        <- declareTopology(client, cfg)
          consumer <- client.createAutoAckConsumer[String](QueueName(cfg.queue))
          logger   <- Slf4jLogger.create[F]
        yield (consumer, logger)

      for
        supervisor <- Supervisor[F](await = false)
        pair       <- Resource.eval(acquire)
        (consumer, logger) = pair
        _ <- Resource.eval(supervisor.supervise(compile(consumer, logger).compile.drain))
      yield ()
    }

  private def declareTopology[F[_]: Sync](client: RabbitClient[F], cfg: RabbitStreamConfig)(using
      channel: AMQPChannel
  ): F[Unit] =
    for
      _ <- client.declareQueue(DeclarationQueueConfig.default(QueueName(cfg.queue)))
      _ <- client.declareExchange(ExchangeName(cfg.exchange), ExchangeType.Topic)
      _ <- cfg.routingKeys.traverse_ { rk =>
             client.bindQueue(QueueName(cfg.queue), ExchangeName(cfg.exchange), RoutingKey(rk))
           }
    yield ()

  private def decodeThen[F[_], A: Decoder](
      env: AmqpEnvelope[String],
      logger: Logger[F],
      dispatch: A => F[Unit]
  ): F[Unit] =
    decode[A](env.payload) match
      case Right(msg) => dispatch(msg)
      case Left(err)  => logger.warn(err)(s"Ignoring malformed event in ${env.payload}")
