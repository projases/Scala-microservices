package edu.uoc.epcsd.course.service

import java.time.LocalDate

import cats.Monad
import cats.Parallel
import cats.effect.Clock
import cats.syntax.all.*
import cats.data.EitherT

import edu.uoc.epcsd.course.domain.*
import edu.uoc.epcsd.course.domain.Eff.{ensure, fromEither, fromOption, liftF}

/** Pure business logic for the Course service.
  *
  *  Depends only on the algebras (repositories + external services) and the effect `F[_]`.
  *  Every operation returns `Eff[F, A] = EitherT[F, CourseError, A]`, so failures short-circuit
  *  cleanly and the HTTP layer simply runs the effect and maps the error.
  */
class CourseService[F[_]: Monad: Parallel](
    courseRepo: CourseRepository[F],
    enrollmentRepo: EnrollmentRepository[F],
    userSvc: UserService[F],
    microcredSvc: MicrocredentialService[F],
    clock: Clock[F],
    eventPublisher: CourseEventPublisher[F]
):

  def getCourseById(courseId: Long): F[Option[Course]] =
    courseRepo.getCourseById(courseId)

  def findCourses: F[List[Course]] =
    courseRepo.findCourses

  def getEnrollmentsByCourse(courseId: Long): F[List[Enrollment]] =
    // *> is the "and then" operator from cats syntax. It runs the first effect (getCourseOrFail) and discards its result, then runs the second effect (findEnrollmentByCourse) and returns its result. This is useful when you want to ensure that the course exists before fetching enrollments, but you don't need the course itself for the next step.
    getCourseOrFail(courseId).value.as(courseId) *> enrollmentRepo.findEnrollmentByCourse(courseId)

  def getEnrollmentById(enrollmentId: Long): F[Option[Enrollment]] =
    enrollmentRepo.getEnrollmentById(enrollmentId)

  def getEnrolledStudents(courseId: Long): F[List[User]] =
    getCourseOrFail(courseId).value *> enrollmentRepo
      .findEnrollmentByCourse(courseId)
      .flatMap(_.parTraverse(enrollment => userSvc.getUserByEmail(enrollment.student)))
      .map(_.flatten)

  def createCourse(req: CreateCourse): Eff[F, Long] =
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
      isInstr <- liftF(userSvc.isInstructor(course.instructor))
      _       <- ensure(isInstr, CourseError.InstructorNotFound(course.instructor))
      existing <- liftF(courseRepo.findCourses)
      _        <- ensure(!existing.exists(_.title == course.title),
                    CourseError.DuplicateCourseTitle(course.title))
      saved  <- liftF(courseRepo.createCourse(course))
    yield saved.id.getOrElse(
      throw new IllegalStateException("createCourse must persist a Course with an id")
    )

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
                    Enrollment(None, email, today, 0L, EnrollmentStatus.Active, courseId)))
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

  def closeCourse(courseId: Long): Eff[F, Unit] =
    for
      course      <- getCourseOrFail(courseId)
      _ <- microcredSvc.requestMicrocredentials(courseId)
      enrollments <- liftF(enrollmentRepo.findEnrollmentByCourse(courseId))
      _           <- ensure(enrollments.forall(_.status == EnrollmentStatus.Graded),
                     CourseError.EnrollmentsNotGraded(courseId))
      _           <- liftF(courseRepo.persistCourseClosure(
                       course, enrollments.map(_.copy(status = EnrollmentStatus.Closed))))
      _           <- liftF(eventPublisher.publishClosed(course))
    yield ()

// This basically returns the course if it exists, or fails with a CourseError if it doesn't. It uses the EitherT monad transformer to lift the Option returned by the repository into an Eff[F, Course], which can then be used in for-comprehensions to chain operations that may fail with a CourseError.
// What would happend if I only used Either? it would not allow you to compose the effectful operations in a clean way. You would have to manually handle the Option and convert it to an Either, which would make the code more verbose and harder to read. Using EitherT allows you to work with the effectful computations in a more functional style, making it easier to handle errors and compose operations.
// 
  private def getCourseOrFail(courseId: Long): Eff[F, Course] =
    EitherT.fromOptionF(courseRepo.getCourseById(courseId), CourseError.CourseNotFound(courseId))
