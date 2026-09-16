package edu.uoc.epcsd.course.service

import java.time.LocalDate

import cats.Monad
import cats.data.EitherT
import cats.effect.Clock
import cats.syntax.all.*

import edu.uoc.epcsd.course.domain.*
import edu.uoc.epcsd.course.domain.Eff.{ensure, fromEither, fromOption, liftF}

/** Command side: one guarded state transition per method, self-contained.
  *
  *  Each transition loads the aggregate, validates its guard, and persists. There is no shared
  *  helper soup across ten methods — the failure modes are local to each command.
  */
class CourseCommands[F[_]: Monad](
    courseRepo: CourseRepository[F],
    enrollmentRepo: EnrollmentRepository[F],
    userSvc: UserService[F],
    clock: Clock[F]
):

  def createCourse(req: CreateCourse): Eff[F, Long] =
    for
      course <- fromEither(
        NewCourse
          .fromRequest(
            req.instructor, req.title, req.description,
            req.enrollmentStartDate, req.enrollmentEndDate,
            req.mode, req.price, req.objectives, req.methology,
            req.duration, req.language, req.location
          )
          .toRight(CourseError.InvalidEnrollmentDates(req.enrollmentStartDate, req.enrollmentEndDate))
      )
      isInstructor  <- liftF(userSvc.isInstructor(course.instructor))
      _        <- ensure(isInstructor, CourseError.InstructorNotFound(course.instructor))
      courses <- liftF(courseRepo.findCourses)
      _        <- ensure(!courses.exists(_.title == course.title),
                    CourseError.DuplicateCourseTitle(course.title))
      saved    <- liftF(courseRepo.createCourse(course))
    yield saved.id

  def modifyCourseDetails(courseId: Long, req: CourseRequestUpdate): Eff[F, Unit] =
    for
      course  <- getCourseOrFail(courseId)
      updated  = course.copy(
                   title = req.title,
                   description = req.description,
                   mode = req.mode,
                   objectives = req.objectives,
                   methology = req.methology,
                   duration = req.duration,
                   language = req.language
                 )
      _ <- liftF(courseRepo.updateCourse(updated))
    yield ()

  def openEnrollment(courseId: Long, start: LocalDate, end: LocalDate): Eff[F, Unit] =
    for
      course <- getCourseOrFail(courseId)
      _      <- ensure(course.canOpenEnrollment,
                 CourseError.InvalidState("enrollment must be DRAFT to open", course.status.toString))
      _      <- fromEither(if Course.validDates(start, end) then Right(()) else Left(CourseError.InvalidEnrollmentDates(start, end)))
      _      <- liftF(courseRepo.updateCourse(
                  course.copy(status = CourseStatus.EnrollmentOpen,
                              enrollmentStartDate = start,
                              enrollmentEndDate = end)))
    yield ()

  def closeEnrollment(courseId: Long): Eff[F, Unit] =
    for
      course <- getCourseOrFail(courseId)
      _      <- ensure(course.status == CourseStatus.EnrollmentOpen,
                 CourseError.InvalidState("enrollment must be ENROLLMENT_OPEN to close", course.status.toString))
      _      <- liftF(courseRepo.updateCourse(course.copy(status = CourseStatus.Active)))
    yield ()

  def enrollInCourse(courseId: Long, email: String): Eff[F, Unit] =
    for
      course <- getCourseOrFail(courseId)
      _      <- ensure(course.status == CourseStatus.EnrollmentOpen,
                 CourseError.InvalidState("course must be ENROLLMENT_OPEN to enroll", course.status.toString))
      userOpt <- liftF(userSvc.getUserByEmail(email))
      _       <- fromOption(userOpt, CourseError.UserNotFound(email, isInstructor = false))
      today   <- liftF(clock.realTimeInstant.map(i => LocalDate.ofInstant(i, java.time.ZoneOffset.UTC)))
      _       <- liftF(enrollmentRepo.createEnrollment(
                   NewEnrollment(email, today, 0L, EnrollmentStatus.Active, courseId)))
    yield ()

  def closeGradeReports(courseId: Long): Eff[F, Unit] =
    for
      course <- getCourseOrFail(courseId)
      _      <- ensure(course.status == CourseStatus.Active,
                 CourseError.InvalidState("course must be ACTIVE to close grade reports", course.status.toString))
      enrollments <- liftF(enrollmentRepo.findEnrollmentByCourse(courseId))
      _           <- liftF(courseRepo.persistGradeReportClosure(
                       course, enrollments.map(_.copy(status = EnrollmentStatus.Graded))))
    yield ()

  private def getCourseOrFail(courseId: Long): Eff[F, Course] =
    EitherT.fromOptionF(courseRepo.getCourseById(courseId), CourseError.CourseNotFound(courseId))
