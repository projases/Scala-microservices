package edu.uoc.epcsd.microcredential.service

import cats.Monad
import cats.effect.Clock
import cats.syntax.all.*

import edu.uoc.epcsd.microcredential.domain.*
import edu.uoc.epcsd.microcredential.domain.Eff.{cond, fromOption, liftF}

/** Pure business logic for the Microcredential service.
  *
  *  Depends only on the algebras (repository, course client, event publisher) and the effect `F`.
  *  State transitions guard on `status == Requested`; requesting microcredentials for a course is
  *  idempotent per-enrollment so the course service's retried `POST /create` can't double-insert.
  */
class MicrocredentialService[F[_]: Monad](
    repo: MicrocredentialRepository[F],
    courseSvc: CourseService[F],
    publisher: MicrocredentialEventPublisher[F],
    clock: Clock[F]
):

  def getMicrocredentialById(id: Long): F[Option[Microcredential]] =
    repo.getById(id)

  def getPendingMicrocredentialRequests: F[List[Microcredential]] =
    repo.getPendingRequests

  def approvePendingMicrocredential(id: Long): Eff[F, Unit] =
    transition(id, MicrocredentialStatus.Granted, MicrocredentialEventPublisher.Approved)

  def rejectPendingMicrocredential(id: Long): Eff[F, Unit] =
    transition(id, MicrocredentialStatus.Rejected, MicrocredentialEventPublisher.Rejected)

  /** Creates a REQUESTED microcredential per enrollment of the course, publishing a pending event
    *  for each. Idempotent: an enrollment already holding a microcredential is left untouched.
    */
  def requestCourseMicrocredentials(courseId: Long): Eff[F, Unit] =
    for
      enrollments <- courseSvc.getCourseEnrollments(courseId)
      _           <- cond(enrollments.nonEmpty, (), MicrocredentialError.NoEnrollments(courseId))
      _           <- liftF(enrollments.traverse_(createForEnrollment))
    yield ()

  private def transition(id: Long, target: MicrocredentialStatus, cmd: String): Eff[F, Unit] =
    for
      mcOpt <- liftF(repo.getById(id))
      m     <- fromOption(mcOpt, MicrocredentialError.NotFound(id))
      _     <- cond(m.status == MicrocredentialStatus.Requested, (),
               MicrocredentialError.InvalidState("REQUESTED", m.status.toString))
      now    <- liftF(clock.realTimeInstant)
      updated = m.copy(status = target, assignmentDate = Some(now))
      _      <- liftF(repo.update(updated))
      enroll <- courseSvc.getEnrollment(m.enrollment)
      _      <- liftF(publisher.publish(cmd, messageFor(updated, enroll)))
    yield ()

  private def createForEnrollment(e: EnrollmentResponse): F[Unit] =
    repo.getByEnrollment(e.id).flatMap {
      case Some(_) => ().pure[F] // already requested: retry-safe
      case None =>
        for
          now <- clock.realTimeInstant
          m   <- repo.create(Microcredential.request(e.id, now))
          _   <- publisher.publish(MicrocredentialEventPublisher.Pending, messageFor(m, e))
        yield ()
    }

  private def messageFor(m: Microcredential, e: EnrollmentResponse): MicrocredentialEventMessage =
    MicrocredentialEventMessage(m.id, e.student, e.courseId, e.id)