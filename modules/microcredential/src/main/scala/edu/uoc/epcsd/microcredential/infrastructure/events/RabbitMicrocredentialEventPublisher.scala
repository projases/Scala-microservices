package edu.uoc.epcsd.microcredential.infrastructure.events

import java.nio.charset.StandardCharsets

import cats.data.Kleisli
import cats.effect.std.{Queue, Supervisor}
import cats.effect.{Async, Resource}
import cats.syntax.all.*
import dev.profunktor.fs2rabbit.effects.MessageEncoder
import dev.profunktor.fs2rabbit.interpreter.RabbitClient
import dev.profunktor.fs2rabbit.model.*
import fs2.Stream
import io.circe.Encoder
import io.circe.generic.semiauto.deriveEncoder
import io.circe.syntax.*
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import edu.uoc.epcsd.microcredential.config.RetryConfig
import edu.uoc.epcsd.microcredential.domain.{MicrocredentialEventMessage, MicrocredentialEventPublisher}
import edu.uoc.epcsd.microcredential.infrastructure.Retry

object MicrocredentialEventMessageCodec:
  given Encoder[MicrocredentialEventMessage] = deriveEncoder

/** fs2-rabbit interpreter for microcredential events, mirroring the course module's publisher.
  *
  *  `publish(key, msg)` only enqueues to an unbounded in-memory Queue; a supervised background
  *  fiber drains it with exponential backoff and re-queues on final failure, so a broker outage
  *  delays rather than breaks the workflow. Each `key` (e.g. "pending") is published to its own
  *  topic routing key `baseRoutingKey.<key>` so consumers can bind selectively.
  */
object RabbitMicrocredentialEventPublisher:

  def apply[F[_]: Async](
      client: RabbitClient[F],
      exchange: ExchangeName,
      baseRoutingKey: RoutingKey,
      retry: RetryConfig
  ): Resource[F, MicrocredentialEventPublisher[F]] =
    given MessageEncoder[F, AmqpMessage[String]] =
      Kleisli[F, AmqpMessage[String], AmqpMessage[Array[Byte]]] { m =>
        m.copy(payload = m.payload.getBytes(StandardCharsets.UTF_8)).pure[F]
      }

    client.createConnectionChannel.flatMap { implicit channel =>
      // One publisher per command, so each message can use a distinct topic routing key.
      val acquire
          : F[(Map[String, AmqpMessage[String] => F[Unit]], Queue[F, (String, MicrocredentialEventMessage)], Logger[F])] =
        for
          _         <- client.declareExchange(exchange, ExchangeType.Topic)
          pubs      <- MicrocredentialEventPublisher.commands.toList.traverse { cmd =>
                         client
                           .createPublisher[AmqpMessage[String]](
                             exchange,
                             RoutingKey(s"${baseRoutingKey.value}.$cmd")
                           )
                           .map(cmd -> _)
                       }
          events    <- Queue.unbounded[F, (String, MicrocredentialEventMessage)]
          logger    <- Slf4jLogger.create[F]
        yield (pubs.toMap, events, logger)

      for
        supervisor <- Supervisor[F](await = false)
        triple     <- Resource.eval(acquire)
        (publishers, events, logger) = triple
        _ <- Resource.eval(supervisor.supervise(drain(events, publishers, retry, logger).compile.drain))
      yield new RabbitMicrocredentialEventPublisher[F](events)
    }

  private final class RabbitMicrocredentialEventPublisher[F[_]](events: Queue[F, (String, MicrocredentialEventMessage)])
      extends MicrocredentialEventPublisher[F]:
    def publish(cmd: String, msg: MicrocredentialEventMessage): F[Unit] =
      events.offer(cmd -> msg)

  private def drain[F[_]: Async](
      events: Queue[F, (String, MicrocredentialEventMessage)],
      publishers: Map[String, AmqpMessage[String] => F[Unit]],
      retry: RetryConfig,
      logger: Logger[F]
  ): Stream[F, Unit] =
    Stream.repeatEval(events.take).flatMap { case (cmd, msg) =>
      Stream.eval {
        val publish = publishers.getOrElse(
          cmd,
          (m: AmqpMessage[String]) => logger.warn(s"No publisher for command $cmd; dropping message") *> m.pure[F].void
        )
        publishOne(events, publish, retry, logger, cmd, msg)
      }
    }

  private def publishOne[F[_]: Async](
      events: Queue[F, (String, MicrocredentialEventMessage)],
      publish: AmqpMessage[String] => F[Unit],
      retry: RetryConfig,
      logger: Logger[F],
      cmd: String,
      msg: MicrocredentialEventMessage
  ): F[Unit] =
    Retry
      .retryWithBackoff(publish(encode(msg)), _ => false, retry.baseDelayMillis, retry.maxAttempts)
      .handleErrorWith { err =>
        logger.error(err)(s"Failed to publish $cmd for microcredential ${msg.microcredentialId}; re-queuing") *>
          Async[F].sleep(Retry.backoffDelay(retry.baseDelayMillis, retry.maxAttempts + 1)) *>
          events.offer(cmd -> msg)
      }

  private def encode(msg: MicrocredentialEventMessage): AmqpMessage[String] =
    import MicrocredentialEventMessageCodec.given
    AmqpMessage(msg.asJson.noSpaces, AmqpProperties.empty)
