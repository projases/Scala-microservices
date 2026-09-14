package edu.uoc.epcsd.course.patterns.freemonad

import java.time.LocalDate

import cats.Monad
import cats.arrow.FunctionK
import cats.free.Free

import edu.uoc.epcsd.course.domain.*
import edu.uoc.epcsd.course.domain.Eff.{fromEither, fromOption}
import edu.uoc.epcsd.course.patterns.freemonad.CourseOp.*
import edu.uoc.epcsd.course.service.CreateCourse

/** The course lifecycle as Free monad programs.
  *
  *  Each flow is a `Program[A] = EitherT[Free[CourseOp, _], CourseError, A]`. Unlike EaD (where a
  *  reducer returns a fixed `List[Effect]` up front), here sequencing is monadic: a `flatMap`
  *  binds the *value* returned by one instruction to the choice of the next — e.g. the `Long`
  *  id from `PersistCourse`, or the `Either` from `RequestMicrocredentials`. The same `Eff`
  *  helpers used by the tagless-final service short-circuit domain errors without emitting new
  *  instructions.
  */
object Lifecycle:

  private def lift[A](op: CourseOp[A]): Program[A] =
    Eff.liftF[FreeOp, A](Free.liftF(op))

  private def ensure(cond: Boolean, err: => CourseError): Program[Unit] =
    Eff.ensure[FreeOp](cond, err)

  private def getCourseOrFail(courseId: Long): Program[Course] =
    lift(FetchCourse(courseId)).flatMap(fromOption(_, CourseError.CourseNotFound(courseId)))

  def createCourse(req: CreateCourse): Program[Long] =
    for
      course  <- fromEither(
        Course
          .fromRequest(
            req.instructor, req.title, req.description,
            req.enrollmentStartDate, req.enrollmentEndDate,
            req.mode, req.price, req.objectives, req.methology,
            req.duration, req.language, req.location
          )
          .toRight(CourseError.InvalidEnrollmentDates(req.enrollmentStartDate, req.enrollmentEndDate))
      )
      isInstr <- lift(IsInstructor(course.instructor))
      _       <- ensure(isInstr, CourseError.InstructorNotFound(course.instructor))
      existing <- lift(FetchCourses)
      _        <- ensure(!existing.exists(_.title == course.title),
                    CourseError.DuplicateCourseTitle(course.title))
      id <- lift(PersistCourse(course))
    yield id

  def openEnrollment(courseId: Long, start: LocalDate, end: LocalDate): Program[Unit] =
    for
      course <- getCourseOrFail(courseId)
      _      <- ensure(course.canOpenEnrollment,
                 CourseError.InvalidState("enrollment must be DRAFT to open", course.status.toString))
      _      <- ensure(Course.validDates(start, end),
                 CourseError.InvalidEnrollmentDates(start, end))
      updated  = course.copy(
                   status = CourseStatus.EnrollmentOpen,
                   enrollmentStartDate = start,
                   enrollmentEndDate = end
                 )
      _    <- lift(UpdateCourse(updated))
    yield ()

  def closeEnrollment(courseId: Long): Program[Unit] =
    for
      course <- getCourseOrFail(courseId)
      _      <- ensure(course.status == CourseStatus.EnrollmentOpen,
                 CourseError.InvalidState("enrollment must be ENROLLMENT_OPEN to close", course.status.toString))
      _      <- lift(UpdateCourse(course.copy(status = CourseStatus.Active)))
    yield ()

  def enrollInCourse(courseId: Long, email: String): Program[Unit] =
    for
      course <- getCourseOrFail(courseId)
      _      <- ensure(course.status == CourseStatus.EnrollmentOpen,
                 CourseError.InvalidState("course must be ENROLLMENT_OPEN to enroll", course.status.toString))
      userOpt <- lift(FetchUser(email))
      _       <- fromOption(userOpt, CourseError.UserNotFound(email, isInstructor = false))
      today   <- lift(Tick)
      _       <- lift(PersistEnrollment(
                   Enrollment(None, email, today, 0L, EnrollmentStatus.Active, courseId)))
    yield ()

  def closeGradeReports(courseId: Long): Program[Unit] =
    for
      course      <- getCourseOrFail(courseId)
      _           <- ensure(course.status == CourseStatus.Active,
                      CourseError.InvalidState("course must be ACTIVE to close grade reports", course.status.toString))
      enrollments <- lift(FetchEnrollments(courseId))
      graded       = enrollments.map(_.copy(status = EnrollmentStatus.Graded))
      _           <- lift(PersistGradeClosure(course.copy(status = CourseStatus.PendingClosure), graded))
    yield ()

  def closeCourse(courseId: Long): Program[Unit] =
    for
      course       <- getCourseOrFail(courseId)
      enrollments  <- lift(FetchEnrollments(courseId))
      _             <- ensure(enrollments.forall(_.status == EnrollmentStatus.Graded),
                        CourseError.EnrollmentsNotGraded(courseId))
      mcResult      <- lift(RequestMicrocredentials(courseId))
      _             <- fromEither(mcResult)
      closed         = enrollments.map(_.copy(status = EnrollmentStatus.Closed))
      courseClosed   = course.copy(status = CourseStatus.Closed)
      _             <- lift(PersistCourseClosure(courseClosed, closed))
      _             <- lift(PublishCourseClosed(courseClosed))
    yield ()

  /** Interpret a program with any `CourseOp ~> F` natural transformation.
    *
    *  `run(p)(nt)` folds the `Free` tree with `nt`, yielding the final encoding `F` wrapped
    *  around the `Either[CourseError, A]` outcome — exactly the shape a tagless service returns.
    */
  def run[F[_]: Monad, A](p: Program[A])(nt: FunctionK[CourseOp, F]): F[Either[CourseError, A]] =
    p.value.foldMap(nt)
