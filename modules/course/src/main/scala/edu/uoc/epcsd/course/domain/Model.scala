package edu.uoc.epcsd.course.domain

import java.time.LocalDate

import io.circe.Codec

/** Lifecycle of a course. camelCase Scala cases, UPPER_SNAKE wire values (see Codecs). */
enum CourseStatus:
  case Draft, EnrollmentOpen, Active, PendingClosure, Closed

object CourseStatus:
  given Codec[CourseStatus] = Codecs.upperSnakeCodec(values)

/** Lifecycle of an individual enrollment within a course. */
enum EnrollmentStatus:
  case Active, Graded, Closed

object EnrollmentStatus:
  given Codec[EnrollmentStatus] = Codecs.upperSnakeCodec(values)

enum UserType:
  case Student, Admin, Instructor

object UserType:
  given Codec[UserType] = Codecs.upperSnakeCodec(values)

/** An unsaved course draft: no `id` yet, because the database has not assigned one.
  *  `create` at the repository boundary issues the id (see `Course.fromNewCourse`), so
  *  "updating an unsaved entity" is a compile error instead of a runtime exception.
  */
final case class NewCourse(
    instructor: String,
    title: String,
    description: String,
    enrollmentStartDate: LocalDate,
    enrollmentEndDate: LocalDate,
    mode: String,
    price: Long,
    objectives: String,
    methology: String,
    duration: Long,
    language: String,
    location: String,
    status: CourseStatus
)

object NewCourse:
  /** Validate that the enrollment date window is sane. */
  def validDates(start: LocalDate, end: LocalDate): Boolean =
    !start.isAfter(end)

  /** Build a validated draft from a creation request (the id is assigned by the DB on `create`).
    *  Returns None on invalid dates.
    */
  def fromRequest(
      instructor: String,
      title: String,
      description: String,
      enrollmentStartDate: LocalDate,
      enrollmentEndDate: LocalDate,
      mode: String,
      price: Long,
      objectives: String,
      methology: String,
      duration: Long,
      language: String,
      location: String
  ): Option[NewCourse] =
    if validDates(enrollmentStartDate, enrollmentEndDate) then
      Some(
        NewCourse(
          instructor = instructor,
          title = title,
          description = description,
          enrollmentStartDate = enrollmentStartDate,
          enrollmentEndDate = enrollmentEndDate,
          mode = mode,
          price = price,
          objectives = objectives,
          methology = methology,
          duration = duration,
          language = language,
          location = location,
          status = CourseStatus.Draft
        )
      )
    else None

/** A persisted course. Immutable; state transitions are expressed via `copy`. */
final case class Course(
    id: Long,
    instructor: String,
    title: String,
    description: String,
    enrollmentStartDate: LocalDate,
    enrollmentEndDate: LocalDate,
    mode: String,
    price: Long,
    objectives: String,
    methology: String,
    duration: Long,
    language: String,
    location: String,
    status: CourseStatus
):
  /** Allowed transition to EnrollmentOpen. */
  def canOpenEnrollment: Boolean = status == CourseStatus.Draft

object Course:
  /** Validate that the enrollment date window is sane. */
  def validDates(start: LocalDate, end: LocalDate): Boolean =
    !start.isAfter(end)

  /** Attach the DB-generated id to a validated draft. */
  def fromNewCourse(id: Long, newCourse: NewCourse): Course =
    Course(
      id = id,
      instructor = newCourse.instructor,
      title = newCourse.title,
      description = newCourse.description,
      enrollmentStartDate = newCourse.enrollmentStartDate,
      enrollmentEndDate = newCourse.enrollmentEndDate,
      mode = newCourse.mode,
      price = newCourse.price,
      objectives = newCourse.objectives,
      methology = newCourse.methology,
      duration = newCourse.duration,
      language = newCourse.language,
      location = newCourse.location,
      status = newCourse.status
    )

/** An unsaved enrollment draft: no `id` yet (the DB assigns one on `create`). */
final case class NewEnrollment(
    student: String,
    enrollmentDate: LocalDate,
    qualification: Long,
    status: EnrollmentStatus,
    courseId: Long
)

/** An enrollment of a student (email) in a course. */
final case class Enrollment(
    id: Long,
    student: String,
    enrollmentDate: LocalDate,
    qualification: Long,
    status: EnrollmentStatus,
    courseId: Long
)

object Enrollment:
  /** Attach the DB-generated id to a validated draft. */
  def fromNewEnrollment(id: Long, newEnrollment: NewEnrollment): Enrollment =
    Enrollment(
      id = id,
      student = newEnrollment.student,
      enrollmentDate = newEnrollment.enrollmentDate,
      qualification = newEnrollment.qualification,
      status = newEnrollment.status,
      courseId = newEnrollment.courseId
    )

/** Read-only user projection returned to callers. */
final case class User(
    id: Long,
    fullName: String,
    email: String,
    phoneNumber: String,
    status: UserType
)