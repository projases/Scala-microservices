package edu.uoc.epcsd.course.infrastructure

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration

import cats.effect.Temporal
import cats.syntax.all.*

/** Small, shared exponential-backoff retry used by every client that talks to an external
  *  component (Microcredential REST, RabbitMQ publish). Keeping it in one place means the
  *  resilience policy is defined once and reused everywhere.
  *
  *  Semantics: `maxAttempts` is the number of retries allowed after the initial attempt, so the
  *  operation can be executed up to `maxAttempts + 1` times in the worst case (this preserves the
  *  original MicrocredentialClient behaviour, which its tests pin).
  */
object Retry:

  def backoffDelay(baseDelayMillis: Long, attempt: Int): FiniteDuration =
    FiniteDuration(baseDelayMillis * math.pow(2, attempt.toDouble).toLong, TimeUnit.MILLISECONDS)

  /** Run `attempt`, retrying whenever it raised an exception or produced a value for which
    *  `shouldRetry` is true, sleeping `baseDelayMillis * 2^attempt` before each retry.
    */
  def retryWithBackoff[F[_], A](
      attempt: F[A],
      shouldRetry: A => Boolean,
      baseDelayMillis: Long,
      maxAttempts: Int
  )(using T: Temporal[F]): F[A] =
    def loop(remaining: Int): F[A] =
      attempt.attempt.flatMap {
        case Right(a) if shouldRetry(a) && remaining > 0 =>
          T.sleep(backoffDelay(baseDelayMillis, remaining)) *> loop(remaining - 1)
        case Right(a) => a.pure[F]
        case Left(_) if remaining > 0 =>
          T.sleep(backoffDelay(baseDelayMillis, remaining)) *> loop(remaining - 1)
        case Left(err) => T.raiseError(err)
      }
    loop(maxAttempts)