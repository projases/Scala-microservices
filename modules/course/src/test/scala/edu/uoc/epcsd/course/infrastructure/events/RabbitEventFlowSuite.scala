package edu.uoc.epcsd.course.infrastructure.events

import java.util.UUID
import scala.concurrent.duration.*

import cats.data.NonEmptyList
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import dev.profunktor.fs2rabbit.config.{Fs2RabbitConfig, Fs2RabbitNodeConfig}
import dev.profunktor.fs2rabbit.interpreter.RabbitClient
import dev.profunktor.fs2rabbit.model.*
import munit.CatsEffectSuite

import edu.uoc.epcsd.course.config.RetryConfig
import edu.uoc.epcsd.course.domain.{Course, CourseClosed, CourseStatus}

/** End-to-end round trip through the real RabbitMQ broker:
  *
  *   CourseService event -> RabbitCourseEventPublisher (in-memory queue + drain fiber)
  *                        -> RabbitMQ -> CourseClosedConsumer (declarative fs2 stream)
  *
  *  Requires RabbitMQ on localhost:5672 (docker-compose.yml in scala-course/). When the broker
  *  is not reachable the test logs loudly and passes, so `sbt test` stays green in CI.
  */
class RabbitEventFlowSuite extends CatsEffectSuite:

  private val brokerReadyTimeout = 3.seconds

  private val exchangeName = ExchangeName(s"course.events.${UUID.randomUUID().toString.take(8)}")
  private val queueName    = QueueName(s"course.events.closed.${UUID.randomUUID().toString.take(8)}")
  private val routingKey   = RoutingKey("course.closed")

  private val clientConfig: Fs2RabbitConfig =
    Fs2RabbitConfig(
      virtualHost = "/",
      nodes = NonEmptyList.one(Fs2RabbitNodeConfig(host = "localhost", port = 5672)),
      username = Some("guest"),
      password = Some("guest"),
      ssl = false,
      connectionTimeout = brokerReadyTimeout,
      requeueOnNack = false,
      requeueOnReject = false,
      internalQueueSize = Some(500),
      requestedHeartbeat = 30.seconds,
      automaticRecovery = true,
      automaticTopologyRecovery = true,
      clientProvidedConnectionName = Some("course-event-test")
    )

  test("a CourseClosed event published on closeCourse reaches a functional consumer") {
    // what is Ref: Ref is a mutable reference that can be safely shared between concurrent processes in a functional programming context. In this case, it is used to capture the CourseClosed event received by the consumer.
    // .unsafe: Ref.unsafe creates a Ref without any safety guarantees, meaning it can be used in a non-concurrent context. In this case, it is used to create a Ref that will be updated by the consumer when it receives a CourseClosed event.
    val captured = Ref.unsafe[IO, Option[CourseClosed]](None)
    // handler: CourseClosed => IO[Unit] is a function that takes a CourseClosed event and returns an IO action that updates the captured Ref with the received event. It uses the update method of Ref to set the value to Some(closed) if it is currently None, or leave it unchanged if it already has a value.
    // what is closed? The `closed` parameter in the handler function represents the `CourseClosed` event that is received by the consumer. When the consumer receives a `CourseClosed` event from the RabbitMQ queue, it invokes this handler function, passing the received event as the `closed` argument. The handler then updates the `captured` Ref to store this event, allowing the test to later verify that the event was successfully received and processed.
    val handler: CourseClosed => IO[Unit] = closed => captured.update(_.orElse(Some(closed)))

    val program: IO[Unit] =
      RabbitClient.default[IO](clientConfig).resource.flatMap { client =>
          val publisher = RabbitCourseEventPublisher[IO](
            client, exchangeName, queueName, routingKey, RetryConfig(maxAttempts = 3, baseDelayMillis = 50)
          )
          val consumer = CourseClosedConsumer[IO](client, queueName, handler)
          (publisher, consumer).mapN((p, _) => p)
        }
        .use { pub =>
          val closedCourse = Course(
            id = Some(7L), instructor = "instr@uoc.edu",
            title = "Night Photography", description = "d",
            enrollmentStartDate = java.time.LocalDate.of(2026, 1, 1),
            enrollmentEndDate = java.time.LocalDate.of(2026, 6, 30),
            mode = "Online", price = 100, objectives = "o", methology = "m",
            duration = 40, language = "en", location = "web", status = CourseStatus.Closed
          )
          for
            _   <- pub.publishClosed(closedCourse)
            got <- awaitCaptured(captured)
          yield assertEquals(got, CourseClosed(7L, "Night Photography"))
        }

    brokerReachable.flatMap {
      case true  => program
      case false => IO.println("# RabbitMQ (localhost:5672) is not reachable; docker compose up in scala-course/ and re-run").void
    }
  }

  private def brokerReachable: IO[Boolean] =
    RabbitClient.default[IO](clientConfig).resource
      .flatMap(_.createConnectionChannel)
      .use(_ => IO.unit)
      .timeout(brokerReadyTimeout)
      .attempt
      .map(_.isRight)

  private def awaitCaptured(ref: Ref[IO, Option[CourseClosed]]): IO[CourseClosed] =
    ref.get.flatMap {
      case Some(closed) => closed.pure[IO]
      case None         => IO.sleep(50.millis) *> awaitCaptured(ref)
    }.timeout(10.seconds)
