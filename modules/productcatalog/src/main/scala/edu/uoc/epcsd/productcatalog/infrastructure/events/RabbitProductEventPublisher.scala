package edu.uoc.epcsd.productcatalog.infrastructure.events

import java.nio.charset.StandardCharsets

import cats.data.Kleisli
import cats.effect.std.{Queue, Supervisor}
import cats.effect.{Async, Resource}
import cats.syntax.all.*
import dev.profunktor.fs2rabbit.effects.MessageEncoder
import dev.profunktor.fs2rabbit.interpreter.RabbitClient
import dev.profunktor.fs2rabbit.model.*
import fs2.Stream
import io.circe.generic.semiauto.deriveEncoder
import io.circe.syntax.*
import io.circe.Encoder
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import edu.uoc.epcsd.productcatalog.config.RetryConfig

/** Async outbound event: new units are available on a product. */
trait ProductEventPublisher[F[_]]:
  def publishUnitAvailable(productId: Long): F[Unit]

object ProductEventPublisher:
  final class Noop[F[_]: cats.Applicative] extends ProductEventPublisher[F]:
    def publishUnitAvailable(productId: Long): F[Unit] = cats.Applicative[F].unit

/** Payload for the `product.unit_available` event. */
final case class ProductMessage(productId: Long)

object ProductMessage:
  given Encoder[ProductMessage] = deriveEncoder

/** fs2-rabbit interpreter for `ProductEventPublisher`, mirroring the course module's publisher:
  *  `publishUnitAvailable` enqueues to an unbounded in-memory Queue; a supervised background fiber
  *  drains it with exponential backoff and re-queues on final failure, so a broker outage delays
  *  rather than loses the event.
  */
object RabbitProductEventPublisher:

  def apply[F[_]: Async](
      client: RabbitClient[F],
      exchange: ExchangeName,
      routingKey: RoutingKey,
      retry: RetryConfig
  ): Resource[F, ProductEventPublisher[F]] =
    given MessageEncoder[F, AmqpMessage[String]] =
      Kleisli[F, AmqpMessage[String], AmqpMessage[Array[Byte]]] { m =>
        m.copy(payload = m.payload.getBytes(StandardCharsets.UTF_8)).pure[F]
      }

    client.createConnectionChannel.flatMap { implicit channel =>
      val acquire: F[(AmqpMessage[String] => F[Unit], Queue[F, Long], Logger[F])] =
        for
          _         <- client.declareExchange(exchange, ExchangeType.Topic)
          publisher <- client.createPublisher[AmqpMessage[String]](exchange, routingKey)
          events    <- Queue.unbounded[F, Long]
          logger    <- Slf4jLogger.create[F]
        yield (publisher, events, logger)

      for
        supervisor <- Supervisor[F](await = false)
        triple     <- Resource.eval(acquire)
        (publisher, events, logger) = triple
        _ <- Resource.eval(
               supervisor.supervise(drain(events, publisher, retry, logger).compile.drain)
             )
      yield new RabbitProductEventPublisher[F](events)
    }

  private final class RabbitProductEventPublisher[F[_]](events: Queue[F, Long])
      extends ProductEventPublisher[F]:
    def publishUnitAvailable(productId: Long): F[Unit] =
      events.offer(productId)

  private def drain[F[_]: Async](
      events: Queue[F, Long],
      publish: AmqpMessage[String] => F[Unit],
      retry: RetryConfig,
      logger: Logger[F]
  ): Stream[F, Unit] =
    Stream.repeatEval(events.take).flatMap { productId =>
      Stream.eval(publishOne(events, publish, retry, logger, productId))
    }

  private def publishOne[F[_]: Async](
      events: Queue[F, Long],
      publish: AmqpMessage[String] => F[Unit],
      retry: RetryConfig,
      logger: Logger[F],
      productId: Long
  ): F[Unit] =
    val attempt = publish(AmqpMessage(ProductMessage(productId).asJson.noSpaces, AmqpProperties.empty))
    attempt.handleErrorWith { err =>
      logger.error(err)(s"Failed to publish product.unit_available for $productId; re-queuing") *>
        Async[F].sleep(backoff(retry.baseDelayMillis, retry.maxAttempts + 1)) *>
        events.offer(productId)
    }

  private def backoff(baseDelayMillis: Long, attempt: Int): scala.concurrent.duration.FiniteDuration =
    scala.concurrent.duration.FiniteDuration(baseDelayMillis * math.pow(2, attempt.toDouble).toLong, java.util.concurrent.TimeUnit.MILLISECONDS)
