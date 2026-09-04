package edu.uoc.epcsd.course.infrastructure.events

import cats.effect.std.Supervisor
import cats.effect.{Async, Resource, Sync}
import cats.syntax.all.*
import dev.profunktor.fs2rabbit.config.declaration.DeclarationQueueConfig
import dev.profunktor.fs2rabbit.interpreter.RabbitClient
import dev.profunktor.fs2rabbit.model.*
import fs2.{Pipe, Stream}
import io.circe.parser.decode
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import edu.uoc.epcsd.course.domain.CourseClosed

/** Declarative, functional consumer: the RabbitMQ leg is modelled as a pure fs2 Stream of
  *  domain events, one `evalMap` per event. In the legacy system this consumer lives in the
  *  Notification service (consuming Kafka); here it runs inside the Course service to
  *  demonstrate the same asynchronous pathway with RabbitMQ.
  *
  *  Swap/extend `handler` to deliver real notifications (e.g. emails) — TODO(exercise).
  */
object CourseClosedConsumer:

  def apply[F[_]: Async](
      client: RabbitClient[F],
      queue: QueueName,
      handler: CourseClosed => F[Unit]
  ): Resource[F, Unit] =
    client.createConnectionChannel.flatMap { implicit channel =>
      val acquire: F[(Stream[F, AmqpEnvelope[String]], Logger[F])] =
        for
          _        <- client.declareQueue(DeclarationQueueConfig.default(queue))
          consumer <- client.createAutoAckConsumer[String](queue)
          logger   <- Slf4jLogger.create[F]
        yield (consumer, logger)

      for
        supervisor <- Supervisor[F](await = false)
        pair       <- Resource.eval(acquire)
        (consumer, logger) = pair
        _ <- Resource.eval(
               supervisor.supervise(
                 consumer.through(decodeClosed(logger)).evalMap(handler).compile.drain
               )
             )
      yield ()
    }

  private def decodeClosed[F[_]: Sync](logger: Logger[F]): Pipe[F, AmqpEnvelope[String], CourseClosed] =
    _.evalMap { env =>
      decode[CourseClosed](env.payload) match
        case Right(closed) => Option(closed).pure[F]
        case Left(err)     => logger.warn(err)(s"Ignoring malformed course.closed event in ${env.payload}").map(_ => Option.empty[CourseClosed])
    }.unNone

  def loggingHandler[F[_]: Sync]: CourseClosed => F[Unit] =
    val logger = Slf4jLogger.getLogger[F]
    closed => logger.info(s"NOTIFICATION: course ${closed.courseId} (${closed.title}) has been closed; would send email")