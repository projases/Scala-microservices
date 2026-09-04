package edu.uoc.epcsd.course.infrastructure.microcredential

import cats.data.EitherT
import cats.effect.Temporal
import cats.syntax.all.*
import org.http4s.*
import org.http4s.client.Client

import edu.uoc.epcsd.course.config.RetryConfig
import edu.uoc.epcsd.course.domain.*
import edu.uoc.epcsd.course.infrastructure.Retry

/** http4s client interpreter for the Microcredential service.
  *
  *  The retry policy lives entirely inside this interpreter, keeping the pure `Eff` algebra
  *  free of resilience concerns. It retries only on connection failures / 5xx (never 4xx)
  *  with the shared exponential backoff from `infrastructure.Retry`.
  *
  *  Safety note: retrying `POST /microcredentials/{courseId}/create` is only valid because the
  *  endpoint is required to be idempotent — see SPECIFICATION §10.3.
  */
class MicrocredentialClient[F[_]](client: Client[F], baseUrl: String, retry: RetryConfig)(using
    T: Temporal[F]
) extends MicrocredentialService[F]:

  def requestMicrocredentials(courseId: Long): Eff[F, Unit] =
    val req = Request[F](
      Method.POST,
      Uri.unsafeFromString(s"$baseUrl/microcredentials/$courseId/create")
    )
    // Connection failures are folded into the error channel so they become retryable too.
    val attempt: F[Either[CourseError, Status]] =
      client
        .run(req)
        .use(resp => resp.status.asRight[CourseError].pure[F])
        .handleError(_ => Left(CourseError.MicrocredentialServiceUnavailable("connection failed")))

    EitherT(
      Retry
        .retryWithBackoff(attempt, isRetryable, retry.baseDelayMillis, retry.maxAttempts)
        .map {
          case Right(status) if status.isSuccess          => Right(())
          case Right(status)                              => Left(CourseError.MicrocredentialServiceUnavailable(s"status $status"))
          case Left(err)                                  => Left(err)
        }
    )

  private def isRetryable(result: Either[CourseError, Status]): Boolean =
    result match
      case Right(status) => status.code >= 500 // all server errors, incl. 503 Service Unavailable
      case Left(_)       => true
