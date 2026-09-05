package edu.uoc.epcsd.microcredential.domain

import cats.Applicative
import cats.Functor
import cats.data.EitherT

// Type definition for the effect type used in the microcredential domain, which is an EitherT that wraps a computation in a context F[_] and can return either a MicrocredentialError or a successful result of type A.
type Eff[F[_], A] = EitherT[F, MicrocredentialError, A]

object Eff:
  def liftF[F[_]: Applicative, A](fa: F[A]): Eff[F, A]                       = EitherT.liftF(fa)
// what is the difference between liftF and right? in this context, `liftF` takes a computation in the context `F[_]` that produces a value of type `A` and lifts it into the `Eff[F, A]` context, which can also represent an error. On the other hand, `right` takes a value of type `A` and directly wraps it in the `Eff[F, A]` context as a successful result, without any computation in the context `F[_]`.
  def right[F[_]: Applicative, A](a: A): Eff[F, A]                           = EitherT.rightT(a)
  def left[F[_]: Applicative, A](e: MicrocredentialError): Eff[F, A]         = EitherT.leftT(e)
  def fromOption[F[_]: Applicative, A](o: Option[A], e: => MicrocredentialError): Eff[F, A] =
    EitherT.fromOption(o, e)
// what is the difference between fromOption and fromOptionF? `fromOption` takes a plain `Option[A]` and an error value, and lifts it into the `Eff[F, A]` context, returning a successful result if the option is `Some(a)` or an error if it is `None`. In contrast, `fromOptionF` takes a computation in the context `F[_]` that produces an `Option[A]`, allowing for asynchronous or effectful computations that may yield an optional value, and similarly lifts it into the `Eff[F, A]` context with the provided error for the `None` case.
  def fromOptionF[F[_]: Functor, A](fa: F[Option[A]], e: => MicrocredentialError): Eff[F, A] =
    EitherT.fromOptionF(fa, e)
// what is the difference between cond and ensure? `cond` takes a boolean condition, a successful value, and an error value, and returns an `Eff[F, A]` that is successful if the condition is true or an error if it is false. It is used for simple conditional checks. On the other hand, `ensure` takes an existing `Eff[F, A]`, a predicate function to check the successful value, and an error value. It allows for more complex validation of the result of a computation, returning an error if the predicate fails.
  def cond[F[_]: Applicative, A](b: Boolean, ok: => A, err: => MicrocredentialError): Eff[F, A] =
    EitherT.cond(b, ok, err)

  def ensure[F[_]: Functor, A](ea: Eff[F, A], p: A => Boolean, err: => MicrocredentialError): Eff[F, A] =
    ea.ensure(err)(p)

// Database repository for microcredentials. The implementation is in the `infrastructure` module, using Doobie and Postgres. The service layer depends only on this algebra, not on the implementation.

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
