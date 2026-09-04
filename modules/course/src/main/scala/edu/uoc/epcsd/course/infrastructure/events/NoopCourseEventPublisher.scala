package edu.uoc.epcsd.course.infrastructure.events

import cats.Applicative

import edu.uoc.epcsd.course.domain.*
import edu.uoc.epcsd.course.domain.CourseEventPublisher

/** Empty interpreter used when `rabbit.enabled = false` so the service still boots without a
  *  broker. The service logic itself never changes — only the wiring chooses the interpreter.
  */
object NoopCourseEventPublisher:
  def apply[F[_]](using F: Applicative[F]): CourseEventPublisher[F] =
    new CourseEventPublisher[F]:
      def publishClosed(course: Course): F[Unit] = F.unit