package edu.uoc.epcsd.course.service

import java.time.LocalDate

import cats.Monad
import cats.Parallel
import cats.effect.Clock

import edu.uoc.epcsd.course.domain.*

/** Slim composition root over the per-capability services.
  *
  *  The HTTP layer talks to one class; behaviour is delegated to `CourseQueries` (reads, plain
  *  `F`), `CourseCommands` (one guarded transition per method) and `CourseClosure` (the single
  *  orchestrated flow). Nothing here is threaded through a shared monadic context itself.
  */
class CourseService[F[_]: Monad: Parallel](
    courseRepo: CourseRepository[F],
    enrollmentRepo: EnrollmentRepository[F],
    userSvc: UserService[F],
    microcredSvc: MicrocredentialService[F],
    clock: Clock[F],
    eventPublisher: CourseEventPublisher[F]
):
  private val queries  = new CourseQueries(courseRepo, enrollmentRepo, userSvc)
  private val commands = new CourseCommands(courseRepo, enrollmentRepo, userSvc, clock)
  private val closure  = new CourseClosure(courseRepo, enrollmentRepo, microcredSvc, eventPublisher)

  def getCourseById(courseId: Long): F[Option[Course]] =
    queries.getCourseById(courseId)

  def findCourses: F[List[Course]] =
    queries.findCourses

  def getEnrollmentsByCourse(courseId: Long): F[List[Enrollment]] =
    queries.getEnrollmentsByCourse(courseId)

  def getEnrollmentById(enrollmentId: Long): F[Option[Enrollment]] =
    queries.getEnrollmentById(enrollmentId)

  def getEnrolledStudents(courseId: Long): F[List[User]] =
    queries.getEnrolledStudents(courseId)

  def createCourse(req: CreateCourse): Eff[F, Long] =
    commands.createCourse(req)

  def modifyCourseDetails(courseId: Long, req: CourseRequestUpdate): Eff[F, Unit] =
    commands.modifyCourseDetails(courseId, req)

  def openEnrollment(courseId: Long, start: LocalDate, end: LocalDate): Eff[F, Unit] =
    commands.openEnrollment(courseId, start, end)

  def closeEnrollment(courseId: Long): Eff[F, Unit] =
    commands.closeEnrollment(courseId)

  def enrollInCourse(courseId: Long, email: String): Eff[F, Unit] =
    commands.enrollInCourse(courseId, email)

  def closeGradeReports(courseId: Long): Eff[F, Unit] =
    commands.closeGradeReports(courseId)

  def closeCourse(courseId: Long): Eff[F, Unit] =
    closure.closeCourse(courseId)