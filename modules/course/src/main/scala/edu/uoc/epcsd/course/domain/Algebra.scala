package edu.uoc.epcsd.course.domain

import cats.Applicative
import cats.data.EitherT

/** Effect alias used across the service layer: `CourseError` on the left channel.
  *  Both the error short-circuit and the effect are threaded in a single `for`-comprehension.
 */
// What is this type? EitherT is a monad transformer that combines the effects of two monads: Either and F. In this case, it represents a computation that can either result in a CourseError (the left channel) or a successful value of type A (the right channel), while also being wrapped in an effect F (like Future, IO, etc.). This allows for error handling and effectful computations to be composed together in a clean and functional way.
type Eff[F[_], A] = EitherT[F, CourseError, A]

object Eff:
  def liftF[F[_]: Applicative, A](fa: F[A]): Eff[F, A]                  = EitherT.liftF(fa)
  def right[F[_]: Applicative, A](a: A): Eff[F, A]                      = EitherT.rightT(a)
  def left[F[_]: Applicative, A](e: CourseError): Eff[F, A]             = EitherT.leftT(e)
  def fromEither[F[_]: Applicative, A](e: Either[CourseError, A]): Eff[F, A] = EitherT.fromEither(e)
  def fromOption[F[_]: Applicative, A](o: Option[A], err: => CourseError): Eff[F, A] =
    EitherT.fromOption(o, err)
  def ensure[F[_]: Applicative](c: Boolean, err: => CourseError): Eff[F, Unit] =
    if c then right(()) else left(err)

/** Persistence interface for courses. Kept honest (plain `F[...]`); the service layer
  *  decides when an empty `Option` is a domain error and lifts it into `Eff`.
  */
trait CourseRepository[F[_]]:
  def getCourseById(courseId: Long): F[Option[Course]]
  def findCourses: F[List[Course]]
  def createCourse(c: Course): F[Course]
  def updateCourse(c: Course): F[Unit]

  /** Persist a grade-report closure atomically: mark the given enrollments Graded and the course
    *  PendingClosure in a single DB transaction. The service supplies the target states.
    */
  def persistGradeReportClosure(course: Course, gradedEnrollments: List[Enrollment]): F[Unit]

  /** Persist a course closure atomically: mark the given enrollments Closed and the course Closed
    *  in a single DB transaction. The service supplies the target states.
    */
  def persistCourseClosure(course: Course, closedEnrollments: List[Enrollment]): F[Unit]

/** Persistence interface for enrollments. */
trait EnrollmentRepository[F[_]]:
  def findEnrollmentByCourse(courseId: Long): F[List[Enrollment]]
  def findEnrollmentByStudent(email: String): F[Option[Enrollment]]
  def getEnrollmentById(id: Long): F[Option[Enrollment]]
  def createEnrollment(e: Enrollment): F[Enrollment]
  def updateEnrollment(e: Enrollment): F[Unit]

/** External User service (REST), see infrastructure.user.UserClient. */
trait UserService[F[_]]:
  def getUserByEmail(email: String): F[Option[User]]
  // def getUsersByEmails(emails: List[String]): F[List[User]]
  def isInstructor(email: String): F[Boolean]

/** External Microcredential service (REST), see infra.microcredential. */
trait MicrocredentialService[F[_]]:
  /** Must be idempotent for a given courseId (retry-safe). */
  def requestMicrocredentials(courseId: Long): Eff[F, Unit]

/** Async outbound events (RabbitMQ via fs2-rabbit, see infra.events).
  *
  *  The interpreter only enqueues to an in-memory queue drained by a background fiber, so a
  *  busy or down broker never blocks, delays or fails the course workflow — teaching point:
  *  emitting a domain event is decoupled from the broker by construction.
  */
trait CourseEventPublisher[F[_]]:
  def publishClosed(course: Course): F[Unit]
