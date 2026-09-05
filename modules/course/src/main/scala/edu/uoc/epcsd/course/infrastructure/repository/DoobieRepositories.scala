package edu.uoc.epcsd.course.infrastructure.repository

import java.time.LocalDate

import cats.effect.MonadCancel
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import doobie.util.transactor.Transactor

import edu.uoc.epcsd.course.domain.*

object DoobieMappings:
    /** meta for enums stored as their UPPER_SNAKE wire form in the DB  This just because of the way they are stored in Java enum convention. */
  private def toEnumName(snake: String): String = Codecs.fromUpperSnakeCase(snake)  

//  bidirectional mapping between Scala types and database column types. 
  given Meta[CourseStatus] =  
    Meta[String].timap(s => CourseStatus.valueOf(toEnumName(s)))(c => Codecs.toUpperSnakeCase(c.toString))  
  given Meta[EnrollmentStatus] =
    Meta[String].timap(s => EnrollmentStatus.valueOf(toEnumName(s)))(c => Codecs.toUpperSnakeCase(c.toString))
  given Meta[UserType] =
    Meta[String].timap(s => UserType.valueOf(toEnumName(s)))(c => Codecs.toUpperSnakeCase(c.toString))

import DoobieMappings.given

/** Doobie interpreter for CourseRepository. */
// MonadCancel so we can safely cancel database operations if needed.
class DoobieCourseRepository[F[_]](xa: Transactor[F])(using F: MonadCancel[F, Throwable])
    extends CourseRepository[F]:

  private val selectColumns =
    fr"id, instructor, title, description, enrollmentstartdate, enrollmentenddate, mode, price, objectives, methology, duration, language, location, status"

  private type CourseRow = (Long, String, String, String, LocalDate, LocalDate, String, Long, String, String, Long, String, String, CourseStatus)

  private val courseQuery = fr"SELECT" ++ selectColumns ++ fr"FROM course"

  def getCourseById(courseId: Long): F[Option[Course]] =
    (courseQuery ++ fr"WHERE id = $courseId")
      .query[CourseRow]
      .map(toCourse)
      .option
      .transact(xa)

  def findCourses: F[List[Course]] =
    courseQuery
      .query[CourseRow]
      .map(toCourse)
      .to[List]
      .transact(xa)

  def createCourse(c: Course): F[Course] =
    // Insert a new course into the database using a SQL INSERT statement. The `withUniqueGeneratedKeys[Long]("id")` method retrieves the generated ID after the insert operation.
    val insert =
      sql"""INSERT INTO course
            (instructor, title, description, enrollmentstartdate, enrollmentenddate, mode, price, objectives, methology, duration, language, location, status)
            VALUES (${c.instructor}, ${c.title}, ${c.description}, ${c.enrollmentStartDate}, ${c.enrollmentEndDate},
                    ${c.mode}, ${c.price}, ${c.objectives}, ${c.methology}, ${c.duration},
                    ${c.language}, ${c.location}, ${c.status})
         """.update.withUniqueGeneratedKeys[Long]("id")
  // Run the insert operation within a transaction and map the generated ID to create a new Course instance with the assigned ID.
    insert.transact(xa).map(id => c.copy(id = Some(id)))

  def updateCourse(c: Course): F[Unit] =
    updateCourseSql(c).transact(xa)

// 
  def persistGradeReportClosure(course: Course, gradedEnrollments: List[Enrollment]): F[Unit] =
    updateEnrollmentStatuses(course, CourseStatus.PendingClosure, gradedEnrollments, EnrollmentStatus.Graded)
      .transact(xa)

  def persistCourseClosure(course: Course, closedEnrollments: List[Enrollment]): F[Unit] =
    updateEnrollmentStatuses(course, CourseStatus.Closed, closedEnrollments, EnrollmentStatus.Closed)
      .transact(xa)

  /** Improvement over the Java version: single transaction: since lifecycle transitions are atomic, we can update the course and all its enrollments in one transaction. batch update of enrollments: using ANY operator to update multiple enrollments in a single SQL statement, which is more efficient than updating them one by one.  
    */
  private def updateEnrollmentStatuses(
      course: Course,
      courseStatus: CourseStatus,
      enrollments: List[Enrollment],
      enrollmentStatus: EnrollmentStatus
  ): ConnectionIO[Unit] =
    for
      _ <- updateCourseSql(course.copy(status = courseStatus))
      _ <- updateEnrollmentStatusesSql(enrollments, enrollmentStatus)
    yield ()

  private def updateCourseSql(c: Course): ConnectionIO[Unit] =
    sql"""UPDATE course SET
            instructor=${c.instructor}, title=${c.title}, description=${c.description},
            enrollmentstartdate=${c.enrollmentStartDate}, enrollmentenddate=${c.enrollmentEndDate},
            mode=${c.mode}, price=${c.price}, objectives=${c.objectives}, methology=${c.methology},
            duration=${c.duration}, language=${c.language}, location=${c.location}, status=${c.status}
          WHERE id = ${courseId(c)}
       """.update.run.void

  // update single enrrolment.
  // private def updateEnrollmentStatusSql(e: Enrollment, status: EnrollmentStatus): ConnectionIO[Unit] =
  //   sql"""UPDATE enrollment SET status = $status WHERE id = ${enrollmentId(e)}""".update.run.void

 /// update batch of enrollments using ANY 
  private def updateEnrollmentStatusesSql(enrollments: List[Enrollment], status: EnrollmentStatus): ConnectionIO[Unit] =
    val ids = enrollments.map(enrollmentId)
    sql"""UPDATE enrollment SET status = $status WHERE id = ANY($ids)""".update.run.void 


  private def courseId(c: Course): Long = c.id.getOrElse(
    throw new IllegalStateException("Cannot persist a Course with no id")
  )

  private def enrollmentId(e: Enrollment): Long = e.id.getOrElse(
    throw new IllegalStateException("Cannot persist an Enrollment with no id")
  )

  private def toCourse(t: (Long, String, String, String, LocalDate, LocalDate, String, Long, String, String, Long, String, String, CourseStatus)): Course =
    Course(Some(t._1), t._2, t._3, t._4, t._5, t._6, t._7, t._8, t._9, t._10, t._11, t._12, t._13, t._14)

/** Doobie interpreter for EnrollmentRepository. */
// Why MonadCancel? Because we want to be able to cancel the database operations if needed, and MonadCancel provides the necessary capabilities for that. It allows us to work with effects that can be canceled, which is important for long-running or potentially blocking operations like database queries.
class DoobieEnrollmentRepository[F[_]](xa: Transactor[F])(using F: MonadCancel[F, Throwable])
    extends EnrollmentRepository[F]:

  def findEnrollmentByCourse(courseId: Long): F[List[Enrollment]] =
    sql"""SELECT id, student, enrollmentdate, qualification, status, course_id
          FROM enrollment WHERE course_id = $courseId"""
      .query[(Long, String, LocalDate, Long, EnrollmentStatus, Long)]
      .map(toEnrollment)
      .to[List]
      .transact(xa)

  def findEnrollmentByStudent(email: String): F[Option[Enrollment]] =
    sql"""SELECT id, student, enrollmentdate, qualification, status, course_id
          FROM enrollment WHERE student = $email"""
      .query[(Long, String, LocalDate, Long, EnrollmentStatus, Long)]
      .map(toEnrollment)
      .option
      .transact(xa)

  def getEnrollmentById(id: Long): F[Option[Enrollment]] =
    sql"""SELECT id, student, enrollmentdate, qualification, status, course_id
          FROM enrollment WHERE id = $id"""
      .query[(Long, String, LocalDate, Long, EnrollmentStatus, Long)]
      .map(toEnrollment)
      .option
      .transact(xa)

  def createEnrollment(e: Enrollment): F[Enrollment] =
    val insert =
      sql"""INSERT INTO enrollment (student, enrollmentdate, qualification, status, course_id)
            VALUES (${e.student}, ${e.enrollmentDate}, ${e.qualification}, ${e.status}, ${e.courseId})
         """.update.withUniqueGeneratedKeys[Long]("id")
    insert.transact(xa).map(id => e.copy(id = Some(id)))

  def updateEnrollment(e: Enrollment): F[Unit] =
    sql"""UPDATE enrollment SET
            student=${e.student}, enrollmentdate=${e.enrollmentDate},
            qualification=${e.qualification}, status=${e.status}, course_id=${e.courseId}
          WHERE id = ${enrollmentId(e)}
       """.update.run.transact(xa).void

  private def enrollmentId(e: Enrollment): Long = e.id.getOrElse(
    throw new IllegalStateException("Cannot persist an Enrollment with no id")
  )

  private def toEnrollment(t: (Long, String, LocalDate, Long, EnrollmentStatus, Long)): Enrollment =
    Enrollment(Some(t._1), t._2, t._3, t._4, t._5, t._6)
