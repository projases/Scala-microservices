package edu.uoc.epcsd.course.infrastructure.microcredential

import cats.effect.{IO, Ref}
import cats.effect.Resource
import munit.CatsEffectSuite
import org.http4s.{Response, Status, Request}
import org.http4s.client.Client

import edu.uoc.epcsd.course.config.RetryConfig
import edu.uoc.epcsd.course.domain.CourseError.MicrocredentialServiceUnavailable

/** Verifies the retry policy lives in the client: 5xx twice then success => 3 attempts. */
class MicrocredentialClientSuite extends CatsEffectSuite:

  private def stubClient(attemptsRef: Ref[IO, Int], responses: List[Status]): Client[IO] =
    Client { (_: Request[IO]) =>
      for
        _ <- Resource.eval(attemptsRef.update(_ + 1))
        status <- Resource.eval(attemptsRef.get.map { n =>
          responses(Math.min(n - 1, responses.size - 1))
        })
      yield Response[IO](status = status)
    }

  test("retries on 5xx and succeeds") {
    val attempts = Ref.unsafe[IO, Int](0)
    val client = stubClient(attempts, List(Status.ServiceUnavailable, Status.ServiceUnavailable, Status.Ok))
    val mc = new MicrocredentialClient[IO](client, "http://localhost:18085", RetryConfig(3, 10))

    mc.requestMicrocredentials(1L).value.flatMap {
      case Right(_) => attempts.get.map(n => assert(n == 3))
      case Left(e)  => fail(s"expected success, got $e")
    }
  }

  test("fails with MicrocredentialServiceUnavailable after exhausting retries") {
    val attempts = Ref.unsafe[IO, Int](0)
    val client = stubClient(attempts, List(Status.InternalServerError, Status.InternalServerError, Status.InternalServerError, Status.InternalServerError))
    val mc = new MicrocredentialClient[IO](client, "http://localhost:18085", RetryConfig(3, 10))

    mc.requestMicrocredentials(1L).value.flatMap {
      case Left(MicrocredentialServiceUnavailable(_)) => attempts.get.map(n => assert(n == 4))
      case other => fail(s"expected MicrocredentialServiceUnavailable, got $other")
    }
  }

  test("does not retry 4xx") {
    val attempts = Ref.unsafe[IO, Int](0)
    val client = stubClient(attempts, List(Status.BadRequest))
    val mc = new MicrocredentialClient[IO](client, "http://localhost:18085", RetryConfig(3, 10))

    mc.requestMicrocredentials(1L).value.flatMap {
      case Left(MicrocredentialServiceUnavailable(_)) => attempts.get.map(n => assert(n == 1))
      case other => fail(s"expected MicrocredentialServiceUnavailable, got $other")
    }
  }