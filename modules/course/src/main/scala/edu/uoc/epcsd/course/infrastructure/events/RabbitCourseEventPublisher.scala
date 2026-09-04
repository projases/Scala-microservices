package edu.uoc.epcsd.course.infrastructure.events

import java.nio.charset.StandardCharsets

import cats.data.Kleisli
import cats.effect.std.{Queue, Supervisor}
import cats.effect.{Async, Resource}
import cats.syntax.all.*
import dev.profunktor.fs2rabbit.config.declaration.DeclarationQueueConfig
import dev.profunktor.fs2rabbit.effects.MessageEncoder
import dev.profunktor.fs2rabbit.interpreter.RabbitClient
import dev.profunktor.fs2rabbit.model.*
import fs2.Stream
import io.circe.syntax.*
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import edu.uoc.epcsd.course.config.RetryConfig
import edu.uoc.epcsd.course.domain.*
import edu.uoc.epcsd.course.infrastructure.Retry

/** fs2-rabbit interpreter for `CourseEventPublisher`.
  *
  *  publishClosed merely enqueues a `CourseClosed` to an in-memory (unbounded) Queue, so a course
  *  closure is never blocked or failed by the broker. A background fiber (supervised for
  *  cancellation) drains the queue and publishes over an fs2-rabbit channel using the shared
  *  exponential-backoff retry; on final failure the message is re-queued (never dropped), so a
  *  broker outage delays rather than loses notification. The exchange/queue/binding are declared
  *  here (idempotent) — wiring stays declarative and functional.
  */
object RabbitCourseEventPublisher:

  def apply[F[_]: Async](
      client: RabbitClient[F],
      exchange: ExchangeName,
      queue: QueueName,
      routingKey: RoutingKey,
      retry: RetryConfig
  ): Resource[F, CourseEventPublisher[F]] =
    given MessageEncoder[F, AmqpMessage[String]] =
      Kleisli[F, AmqpMessage[String], AmqpMessage[Array[Byte]]] { m =>
        m.copy(payload = m.payload.getBytes(StandardCharsets.UTF_8)).pure[F]
      }

    client.createConnectionChannel.flatMap { implicit channel =>
      val acquire: F[(AmqpMessage[String] => F[Unit], Queue[F, CourseClosed], Logger[F])] =
        for
          _         <- client.declareQueue(DeclarationQueueConfig.default(queue))
          _         <- client.declareExchange(exchange, ExchangeType.Topic)
          _         <- client.bindQueue(queue, exchange, routingKey)
          publisher <- client.createPublisher[AmqpMessage[String]](exchange, routingKey)
          events    <- Queue.unbounded[F, CourseClosed]
          logger    <- Slf4jLogger.create[F]
        yield (publisher, events, logger)

      for
        supervisor <- Supervisor[F](await = false)
        triple     <- Resource.eval(acquire)
        (publisher, events, logger) = triple
        _ <- Resource.eval(supervisor.supervise(drain(events, publisher, retry, logger).compile.drain))
      yield new RabbitCourseEventPublisher[F](events)
    }

  private final class RabbitCourseEventPublisher[F[_]](events: Queue[F, CourseClosed])
      extends CourseEventPublisher[F]:
    def publishClosed(course: Course): F[Unit] =
      val courseId = course.id.getOrElse(throw new IllegalStateException("Cannot publish a Course with no id"))
      events.offer(CourseClosed(courseId, course.title))

  private def drain[F[_]: Async](
      events: Queue[F, CourseClosed],
      publish: AmqpMessage[String] => F[Unit],
      retry: RetryConfig,
      logger: Logger[F]
  ): Stream[F, Unit] =
    Stream.repeatEval(events.take).flatMap { closed =>
      Stream.eval(publishOne(events, publish, retry, logger, closed))
    }

  private def publishOne[F[_]: Async](
      events: Queue[F, CourseClosed],
      publish: AmqpMessage[String] => F[Unit],
      retry: RetryConfig,
      logger: Logger[F],
      closed: CourseClosed
  ): F[Unit] =
    Retry
      .retryWithBackoff(publish(encode(closed)), _ => false, retry.baseDelayMillis, retry.maxAttempts)
      .handleErrorWith { err =>
        logger.error(err)(s"Failed to publish course.closed for course ${closed.courseId}; re-queuing") *>
          Async[F].sleep(Retry.backoffDelay(retry.baseDelayMillis, retry.maxAttempts + 1)) *>
          events.offer(closed)
      }

  private def encode(closed: CourseClosed): AmqpMessage[String] =
    AmqpMessage(closed.asJson.noSpaces, AmqpProperties.empty)