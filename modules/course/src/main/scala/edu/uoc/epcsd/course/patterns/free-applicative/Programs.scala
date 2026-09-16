package edu.uoc.epcsd.course.patterns.freeapplicative

import cats.free.{Free, FreeApplicative}
import cats.syntax.all.*

import edu.uoc.epcsd.course.domain.*
import edu.uoc.epcsd.course.patterns.freeapplicative.LoadOp.*

/** Two programs for the same job — "the enrollments of a course and their users".
  *
  *  - `loadCourseData`: a `FreeApplicative`. The fan-out over users is *static* — whoever builds
  *    the program hands the emails over up front — so the program's shape is fully known before
  *    it runs. `mapN` is the only combinator: nothing here may wait on an earlier result. In
  *    exchange you can inspect the program, or run it under any `Applicative` — including a
  *    parallel one.
  *  - `loadCourseDataMonadic`: a `Free` monad. The emails are *derived* from the enrollment
  *    load, so the user fetches cannot even be described until the enrollments return. The
  *    dependency forces sequencing, and each user fetch runs one-at-a-time.
  *
  *  The juxtaposition is the whole lesson: **applicative when you can, monad when you must** —
  *  a parallelizable structure is precisely one whose shape you already know.
  */
object Programs:

  /** FreeApplicative: the enrollment load combined with a *static* parallel fan-out of user loads. */
  def loadCourseData(
      courseId: Long,
      emails: List[String]
  ): LoadProgram[(List[Enrollment], List[User])] =
    (
      lift(FindEnrollments(courseId)),
      emails.traverse(email => lift(FindUser(email)))
    ).mapN((enrollments, userOpts) => (enrollments, userOpts.flatten))

  /** The monadic equivalent: the student emails come from the enrollment load, so the user
    *  fan-out must wait for it and runs one fetch after another.
    */
  def loadCourseDataMonadic(courseId: Long): Free[LoadOp, (List[Enrollment], List[User])] =
    for
      enrollments <- Free.liftF(FindEnrollments(courseId))
      users       <- enrollments.traverse(e => Free.liftF(FindUser(e.student)))
    yield (enrollments, users.flatten)

  private def lift[A](op: LoadOp[A]): FreeApplicative[LoadOp, A] =
    FreeApplicative.lift(op)