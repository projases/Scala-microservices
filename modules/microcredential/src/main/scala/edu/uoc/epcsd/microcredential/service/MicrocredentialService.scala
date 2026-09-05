package edu.uoc.epcsd.microcredential.service

import cats.Monad
import cats.Parallel
import cats.effect.Clock
import cats.syntax.all.*

import edu.uoc.epcsd.microcredential.domain.*
import edu.uoc.epcsd.microcredential.domain.Eff.{cond, fromOption, liftF}

/** Pure business logic for the Microcredential service.
  *
  *  Depends only on the algebras (repository, course client, event publisher) and the effect `F`.
  *  State transitions guard on `status == Requested`; requesting microcredentials for a course is
  *  idempotent per-enrollment (via the repo's atomic `ON CONFLICT DO NOTHING`) so the course
  *  service's retried `POST /create` can't double-insert, and the per-enrollment inserts/publishes
  *  run in parallel.
  */
class MicrocredentialService[F[_]: Monad: Parallel](
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
    *  for each created one. Idempotent: the repo's `on conflict do nothing` leaves enrollments that
    *  already hold a microcredential untouched (no duplicate events). Per-enrollment work runs in
    *  parallel.
    */
  def requestCourseMicrocredentials(courseId: Long): Eff[F, Unit] =
    for
      enrollments <- courseSvc.getCourseEnrollments(courseId)
      _           <- cond(enrollments.nonEmpty, (), MicrocredentialError.NoEnrollments(courseId))
      _           <- liftF(enrollments.parTraverse_(createForEnrollment))
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

  /** Atomically inserts a microcredential for `e.id` and, only when it was newly created, publishes
    *  a pending event. A concurrent/retried call for the same enrollment yields `None` and is a no-op.
    */
  private def createForEnrollment(e: EnrollmentResponse): F[Unit] =
    for
      now         <- clock.realTimeInstant
      base         = Microcredential.request(e.id, now)
      insertedId  <- repo.createIfAbsent(base)
      _           <- insertedId match
                       case Some(id) => publisher.publish(
                                         MicrocredentialEventPublisher.Pending, messageFor(base.copy(id = Some(id)), e))
                       case None     => ().pure[F] // enrollment already holds a microcredential
    yield ()

  private def messageFor(m: Microcredential, e: EnrollmentResponse): MicrocredentialEventMessage =
    MicrocredentialEventMessage(requireId(m), e.student, e.courseId, e.id)

  private def requireId(m: Microcredential): Long =
    m.id.getOrElse(throw new IllegalStateException("Cannot publish an event for a Microcredential with no id"))