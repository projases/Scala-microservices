package edu.uoc.epcsd.course.patterns.freeapplicative

import cats.free.FreeApplicative

import edu.uoc.epcsd.course.domain.*

/** The load vocabulary of the Free applicative encoding (an *initial* encoding, applicative only).
  *
  *  `LoadOp[A]` is a GADT: one constructor per read, carrying what that read *returns* in the
  *  type parameter. Unlike the Free monad's `CourseOp`, a `FreeApplicative` program can only
  *  *combine* these leaves — there is **no `flatMap`**: no step may depend on another step's
  *  result. The payoff of that loss is that the shape of a program is known *before it runs*:
  *  the fan-out is static, inspectable, and can be interpreted with a parallel `Applicative`.
  */
enum LoadOp[A]:
  case FindEnrollments(courseId: Long) extends LoadOp[List[Enrollment]]
  case FindUser(email: String) extends LoadOp[Option[User]]

  /** A one-line, human-readable description of what this read would do. */
  def describe: String = this match
    case FindEnrollments(courseId) => s"GET courses/$courseId/enrollments"
    case FindUser(email)           => s"GET users/$email"

/** A FreeApplicative program over the load vocabulary. */
type LoadProgram[A] = FreeApplicative[LoadOp, A]