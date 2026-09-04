package edu.uoc.epcsd.microcredential.infrastructure

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration

import cats.effect.Temporal
import cats.syntax.all.*

object Retry:

  def backoffDelay(baseDelayMillis: Long, attempt: Int): FiniteDuration =
    FiniteDuration(baseDelayMillis * math.pow(2, attempt.toDouble).toLong, TimeUnit.MILLISECONDS)

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
