package edu.uoc.epcsd.course.patterns.effectsasdata

import java.time.LocalDate

import edu.uoc.epcsd.course.domain.*
import edu.uoc.epcsd.course.service.CreateCourse

object Reducers:

  private def ensure(cond: Boolean, err: CourseError): Either[CourseError, Unit] =
    if cond then Right(()) else Left(err)

  private def courseId(c: Course): Long = c.id.getOrElse(0L)

  def createCourse(
      req: CreateCourse,
      instructor: Option[User],
      existing: List[Course]
  ): Either[CourseError, Transition[Course]] =
    for
      course <- Course
                  .fromRequest(
                    req.instructor, req.title, req.description,
                    req.enrollmentStartDate, req.enrollmentEndDate,
                    req.mode, req.price, req.objectives, req.methology,
                    req.duration, req.language, req.location
                  )
                  .toRight(CourseError.InvalidEnrollmentDates(req.enrollmentStartDate, req.enrollmentEndDate))
      _       <- ensure(instructor.exists(_.status == UserType.Instructor),
                        CourseError.InstructorNotFound(req.instructor))
      _       <- ensure(!existing.exists(_.title == req.title),
                        CourseError.DuplicateCourseTitle(req.title))
    yield Transition(course, List(Effect.CreateCourse(course)))

  def openEnrollment(
      course: Course,
      start: LocalDate,
      end: LocalDate
  ): Either[CourseError, Transition[Course]] =
    for
      _ <- ensure(course.canOpenEnrollment,
                  CourseError.InvalidState("enrollment must be DRAFT to open", course.status.toString))
      _ <- ensure(Course.validDates(start, end), CourseError.InvalidEnrollmentDates(start, end))
    yield
      val updated = course.copy(
        status = CourseStatus.EnrollmentOpen,
        enrollmentStartDate = start,
        enrollmentEndDate = end
      )
      Transition(updated, List(Effect.UpdateCourse(updated)))

  def closeEnrollment(course: Course): Either[CourseError, Transition[Course]] =
    for
      _ <- ensure(course.status == CourseStatus.EnrollmentOpen,
                  CourseError.InvalidState("enrollment must be ENROLLMENT_OPEN to close", course.status.toString))
    yield
      val updated = course.copy(status = CourseStatus.Active)
      Transition(updated, List(Effect.UpdateCourse(updated)))

  def enrollInCourse(
      course: Course,
      email: String,
      user: Option[User],
      today: LocalDate
  ): Either[CourseError, Transition[Enrollment]] =
    for
      _ <- ensure(course.status == CourseStatus.EnrollmentOpen,
                  CourseError.InvalidState("course must be ENROLLMENT_OPEN to enroll", course.status.toString))
      _ <- ensure(user.isDefined, CourseError.UserNotFound(email, isInstructor = false))
    yield
      val draft = Enrollment(None, email, today, 0L, EnrollmentStatus.Active, courseId(course))
      Transition(draft, List(Effect.CreateEnrollment(draft)))

  def closeGradeReports(
      course: Course,
      enrollments: List[Enrollment]
  ): Either[CourseError, Transition[Unit]] =
    for
      _ <- ensure(course.status == CourseStatus.Active,
                  CourseError.InvalidState("course must be ACTIVE to close grade reports", course.status.toString))
    yield
      val graded = enrollments.map(_.copy(status = EnrollmentStatus.Graded))
      Transition((), List(Effect.PersistGradeClosure(course.copy(status = CourseStatus.PendingClosure), graded)))

  def closeCourse(
      course: Course,
      enrollments: List[Enrollment]
  ): Either[CourseError, Transition[Unit]] =
    for
      _ <- ensure(enrollments.forall(_.status == EnrollmentStatus.Graded),
                  CourseError.EnrollmentsNotGraded(courseId(course)))
    yield
      val closed       = enrollments.map(_.copy(status = EnrollmentStatus.Closed))
      val courseClosed = course.copy(status = CourseStatus.Closed)
      Transition(
        (),
        List(
          Effect.RequestMicrocredentials(courseId(course)),
          Effect.PersistCourseClosure(courseClosed, closed),
          Effect.PublishCourseClosed(courseClosed)
        )
      )
