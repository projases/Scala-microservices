package edu.uoc.epcsd.course.patterns.freemonad

import java.time.LocalDate

import scala.collection.mutable.ListBuffer

import cats.Monad
import cats.arrow.FunctionK
import cats.effect.Clock
import cats.effect.IO
import cats.syntax.all.*

import edu.uoc.epcsd.course.domain.*
import edu.uoc.epcsd.course.patterns.freemonad.CourseOp.*

/** Natural transformations that turn a `Free[CourseOp, _]` program into a concrete effect.
  *
  *  The point of the initial encoding: the *same* program value in `Lifecycle` runs under any of
  *  these. Each `apply` handles every instruction and returns the concrete `F[A]`.
  */

/** The production-shaped interpreter: wires the same repository / service / event algebras the
  *  tagless-final service uses (doobie-backed in production, `Fakes` in tests). Polymorphic in
  *  `F` so the very same interpreter serves in-memory tests and the real repositories.
 */
// what is functionK? FunctionK is a type class in Cats that represents a natural transformation between two type constructors. In this case, it transforms from CourseOp to F. It allows you to define how to interpret the operations defined in the CourseOp algebra into a concrete effect type F, such as IO or any other effect type that has a Monad instance.
final class DoobieInterpreter[F[_]: Monad](
    clock: Clock[F],
    courseRepo: CourseRepository[F],
    enrollmentRepo: EnrollmentRepository[F],
    userSvc: UserService[F],
    microcredSvc: MicrocredentialService[F],
    eventPublisher: CourseEventPublisher[F]
) extends FunctionK[CourseOp, F]:

  def apply[A](fa: CourseOp[A]): F[A] = fa match
    case IsInstructor(email)             => userSvc.isInstructor(email)
    case FetchCourses                    => courseRepo.findCourses
    case FetchCourse(id)                 => courseRepo.getCourseById(id)
    case FetchEnrollments(courseId)      => enrollmentRepo.findEnrollmentByCourse(courseId)
    case FetchUser(email)                => userSvc.getUserByEmail(email)
    case PersistCourse(c)                => courseRepo.createCourse(c).map(_.id)
    case UpdateCourse(c)                 => courseRepo.updateCourse(c)
    case PersistEnrollment(e)            => enrollmentRepo.createEnrollment(e).map(_.id)
    case PersistGradeClosure(c, es)      => courseRepo.persistGradeReportClosure(c, es)
    case PersistCourseClosure(c, es)     => courseRepo.persistCourseClosure(c, es)
    case RequestMicrocredentials(id)     => microcredSvc.requestMicrocredentials(id).value
    case PublishCourseClosed(c)          => eventPublisher.publishClosed(c)
    case Tick                            => today

  private def today: F[LocalDate] =
    clock.realTimeInstant.map(i => LocalDate.ofInstant(i, java.time.ZoneOffset.UTC))

/** The third interpreter — the payoff of the initial encoding. It journals every instruction
  *  (via `describe`) then delegates to any target natural transformation. Folding a program with
  *  a logging interpreter over an in-memory one yields the exact instruction *script* the program
  *  would issue, asserted in the suite.
  */
final class LoggingInterpreter(delegate: FunctionK[CourseOp, IO]) extends FunctionK[CourseOp, IO]:
  private val log: ListBuffer[String] = ListBuffer.empty

  /** The journaled instruction script, in execution order. */
  def instructions: List[String] = log.toList

  def apply[A](fa: CourseOp[A]): IO[A] =
    log += fa.describe
    delegate(fa)
