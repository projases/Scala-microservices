package edu.uoc.epcsd.course.patterns.freemonad

import java.time.LocalDate

import cats.Functor
import cats.free.Free

import edu.uoc.epcsd.course.domain.*

/** The instruction vocabulary of the Free monad encoding (an *initial* encoding).
  *
  *  `CourseOp[A]` is a GADT: one constructor per effectful operation, carrying the value that
  *  operation *returns* in the type parameter. `PersistCourse` and `PersistEnrollment` return
  *  the generated `Long` id — the value-returning writes that EaD's flat `List[Effect]` cannot
  *  express. `RequestMicrocredentials` exposes its own error channel so the `closeCourse` program
  *  can branch on the write result via `flatMap` (the seam that motivates this encoding).
 */
// CourseOp is a GADT (Generalized Algebraic Data Type) that represents the operations that can be performed in the course domain. Each case of the CourseOp enum corresponds to a specific operation, and the type parameter A indicates the return type of that operation. This allows for a more expressive and type-safe way to define operations compared to a flat list of effects. The describe method provides a human-readable description of each operation, which can be useful for logging or debugging purposes. and to flatmap over the operations in a program, allowing for branching and sequencing of effects based on the results of previous operations.
enum CourseOp[A]:
  case IsInstructor(email: String) extends CourseOp[Boolean]
  case FetchCourses extends CourseOp[List[Course]]
  case FetchCourse(id: Long) extends CourseOp[Option[Course]]
  case FetchEnrollments(courseId: Long) extends CourseOp[List[Enrollment]]
  case FetchUser(email: String) extends CourseOp[Option[User]]
  case PersistCourse(c: Course) extends CourseOp[Long]
  case UpdateCourse(c: Course) extends CourseOp[Unit]
  case PersistEnrollment(e: Enrollment) extends CourseOp[Long]
  case PersistGradeClosure(c: Course, gradedEnrollments: List[Enrollment]) extends CourseOp[Unit]
  case PersistCourseClosure(c: Course, closedEnrollments: List[Enrollment]) extends CourseOp[Unit]
  case RequestMicrocredentials(courseId: Long) extends CourseOp[Either[CourseError, Unit]]
  case PublishCourseClosed(c: Course) extends CourseOp[Unit]
  case Tick extends CourseOp[LocalDate]

  /** A one-line, human-readable journal of what this instruction would do. */
  def describe: String = this match
    case IsInstructor(email)             => s"GET isInstructor($email)"
    case FetchCourses                    => "GET courses"
    case FetchCourse(id)                 => s"GET courses/$id"
    case FetchEnrollments(courseId)      => s"GET courses/$courseId/enrollments"
    case FetchUser(email)                => s"GET users/$email"
    case PersistCourse(c)                => s"INSERT course '${c.title}'"
    case UpdateCourse(c)                 => s"UPDATE course ${idOf(c)} -> ${c.status}"
    case PersistEnrollment(e)            => s"INSERT enrollment for ${e.student}"
    case PersistGradeClosure(c, es)      => s"1 TXN: course ${idOf(c)} -> PENDING_CLOSURE, ${es.size} enrollments -> GRADED"
    case PersistCourseClosure(c, es)     => s"1 TXN: course ${idOf(c)} -> CLOSED, ${es.size} enrollments -> CLOSED"
    case RequestMicrocredentials(cid)    => s"POST microcredentials/$cid/create (idempotent)"
    case PublishCourseClosed(c)          => s"ENQUEUE course.closed for course ${idOf(c)} (async)"
    case Tick                            => "GET clock/now"

  private def idOf(c: Course): String = c.id.fold("<unsaved>")(_.toString)

object CourseOp:
  /** Every case carries the operation's already-decided result type, so `CourseOp` is
    *  covariant in its phantom function argument for free. The `f` is deliberately unused:
    *  there is no `A` value inside any case to apply it to.
   */
    // remind me what is covariant? Covariance is a property of type parameters in programming languages that allows a type to be substituted with its subtypes. In the context of the `CourseOp` GADT, it means that if you have a `CourseOp[A]`, you can treat it as a `CourseOp[B]` if `A` is a subtype of `B`. This is useful because it allows for more flexible and reusable code, especially when working with collections or functions that operate on these types.
  given Functor[CourseOp] with
    def map[A, B](fa: CourseOp[A])(f: A => B): CourseOp[B] =
      // this is a bit of a hack: we are ignoring the function `f` and just returning the same operation, but with the type parameter changed to `B`. This is safe because `CourseOp` is covariant in its type parameter, so we can treat it as a `CourseOp[B]` even though it was originally a `CourseOp[A]`.
      val _ = f
      fa.asInstanceOf[CourseOp[B]]

/** The raw Free tree: `Free[CourseOp, A]` is the program-as-data. */
type FreeOp[A] = Free[CourseOp, A]

/** The full program: the existing `EitherT` error channel around the Free tree.
  *  `Eff[FreeOp, A] = EitherT[FreeOp, CourseError, A]`.
  */
type Program[A] = Eff[FreeOp, A]
