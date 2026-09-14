package edu.uoc.epcsd.course.service

import cats.Monad
import cats.Parallel
import cats.syntax.all.*

import edu.uoc.epcsd.course.domain.*

/** Read side of the course service: pure `F` queries, plain `F`, no `EitherT`, no guards.
  *
  *  Reads are boring by design. The HTTP layer maps an empty result to a 404; the service never
  *  decides on behalf of the caller.
  */
class CourseQueries[F[_]: Monad: Parallel](
    courseRepo: CourseRepository[F],
    enrollmentRepo: EnrollmentRepository[F],
    userSvc: UserService[F]
):

  def getCourseById(courseId: Long): F[Option[Course]] =
    courseRepo.getCourseById(courseId)

  def findCourses: F[List[Course]] =
    courseRepo.findCourses

  def getEnrollmentsByCourse(courseId: Long): F[List[Enrollment]] =
    enrollmentRepo.findEnrollmentByCourse(courseId)

  def getEnrollmentById(enrollmentId: Long): F[Option[Enrollment]] =
    enrollmentRepo.getEnrollmentById(enrollmentId)

  def getEnrolledStudents(courseId: Long): F[List[User]] =
    enrollmentRepo
      .findEnrollmentByCourse(courseId)
      .flatMap(_.parTraverse(enrollment => userSvc.getUserByEmail(enrollment.student)))
      .map(_.flatten)
