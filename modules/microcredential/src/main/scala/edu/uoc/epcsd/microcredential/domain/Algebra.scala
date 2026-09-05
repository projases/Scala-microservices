package edu.uoc.epcsd.microcredential.domain

import cats.Applicative
import cats.Functor
import cats.data.EitherT

type Eff[F[_], A] = EitherT[F, MicrocredentialError, A]

object Eff:
  def liftF[F[_]: Applicative, A](fa: F[A]): Eff[F, A]                       = EitherT.liftF(fa)
  def right[F[_]: Applicative, A](a: A): Eff[F, A]                           = EitherT.rightT(a)
  def left[F[_]: Applicative, A](e: MicrocredentialError): Eff[F, A]         = EitherT.leftT(e)
  def fromOption[F[_]: Applicative, A](o: Option[A], e: => MicrocredentialError): Eff[F, A] =
    EitherT.fromOption(o, e)
  def fromOptionF[F[_]: Functor, A](fa: F[Option[A]], e: => MicrocredentialError): Eff[F, A] =
    EitherT.fromOptionF(fa, e)
  def cond[F[_]: Applicative, A](b: Boolean, ok: => A, err: => MicrocredentialError): Eff[F, A] =
    EitherT.cond(b, ok, err)

trait MicrocredentialRepository[F[_]]:
  def getById(id: Long): F[Option[Microcredential]]
  def getByEnrollment(enrollmentId: Long): F[Option[Microcredential]]

  /** Inserts `m` unless an enrollment already holds a microcredential. Returns the generated id
    *  when the row was created, or `None` when it already existed (atomic `ON CONFLICT DO NOTHING`,
    *  so it is safe under concurrency and retries).
    */
  def createIfAbsent(m: Microcredential): F[Option[Long]]

  def update(m: Microcredential): F[Unit]
  def getPendingRequests: F[List[Microcredential]]

/** External Course service (REST). */
trait CourseService[F[_]]:
  def getCourseEnrollments(courseId: Long): Eff[F, List[EnrollmentResponse]]
  def getEnrollment(enrollmentId: Long): Eff[F, EnrollmentResponse]

/** Outbound RabbitMQ event message. */
final case class MicrocredentialEventMessage(
    microcredentialId: Long,
    userEmail: String,
    courseId: Long,
    enrollment: Long
)

/** Async outbound events (RabbitMQ). `cmd` is the routing-key suffix (e.g. "microcredential.pending"). */
trait MicrocredentialEventPublisher[F[_]]:
  def publish(cmd: String, msg: MicrocredentialEventMessage): F[Unit]

object MicrocredentialEventPublisher:
  val Pending  = "pending"
  val Approved = "approved"
  val Rejected = "rejected"
  val commands: Set[String] = Set(Pending, Approved, Rejected)

  /** An in-memory no-op used when the broker leg is disabled. */
  final class Noop[F[_]: Applicative] extends MicrocredentialEventPublisher[F]:
    def publish(cmd: String, msg: MicrocredentialEventMessage): F[Unit] = Applicative[F].unit
