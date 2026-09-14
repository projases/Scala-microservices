package edu.uoc.epcsd.course.service

import cats.Monad
import cats.data.EitherT

import edu.uoc.epcsd.course.domain.*
import edu.uoc.epcsd.course.domain.Eff.{ensure, liftF}

/** The course-closure orchestration: the one operation in this service whose correctness
  *  depends on the *order* of its side effects across multiple systems.
  *
  *  It lives apart from `CourseCommands` because those are single-step mutations, whereas this
  *  is an **orchestration** — one coordinator driving a fixed sequence of operations over
  *  repositories and an external service. The ordering is load-bearing: the all-Graded guard
  *  runs before `requestMicrocredentials` (an irreversible outside-world effect,
  *  so it must never fire from a failed state), and `publishClosed` runs last so a
  *  consumer never hears about a close that was not committed.
  *
  *  If this ever needed compensating actions it would be a *saga*; it is not a *choreography*
  *  (no event-driven hand-off between decoupled participants). This is also the unit the EaD and
  *  Free-monad packages model as their `closeCourse` — the one flow where sequencing can't be
  *  flattened, that is, run in parallel.
  */
class CourseClosure[F[_]: Monad](
    courseRepo: CourseRepository[F],
    enrollmentRepo: EnrollmentRepository[F],
    microcredSvc: MicrocredentialService[F],
    eventPublisher: CourseEventPublisher[F]
):

  def closeCourse(courseId: Long): Eff[F, Unit] =
    for
      course      <- getCourseOrFail(courseId)
      enrollments <- liftF(enrollmentRepo.findEnrollmentByCourse(courseId))
      _           <- ensure(enrollments.forall(_.status == EnrollmentStatus.Graded),
                     CourseError.EnrollmentsNotGraded(courseId))
      _           <- microcredSvc.requestMicrocredentials(courseId)
      _           <- liftF(courseRepo.persistCourseClosure(
                       course, enrollments.map(_.copy(status = EnrollmentStatus.Closed))))
      _           <- liftF(eventPublisher.publishClosed(course))
    yield ()

  private def getCourseOrFail(courseId: Long): Eff[F, Course] =
    EitherT.fromOptionF(courseRepo.getCourseById(courseId), CourseError.CourseNotFound(courseId))
