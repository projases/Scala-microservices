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

/** Kind of user; relevant here only to distinguish instructors from students. */
enum UserType:
  case Student, Admin, Instructor

object UserType:
  given Codec[UserType] = Codecs.upperSnakeCodec(values)

/** A course. Immutable; state transitions are expressed via `copy`. */
final case class Course(
    id: Option[Long],
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

  /** Build a Course from a request: an unsaved draft (id is None until the DB assigns it).
    *  Assumes the caller has already validated the request; returns None on invalid dates.
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
  ): Option[Course] =
    if validDates(enrollmentStartDate, enrollmentEndDate) then
      Some(
        Course(
          id = None,
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
/////////////////////////////////////////////////////////////////
/** An enrollment of a student (email) in a course. */
final case class Enrollment(
    id: Option[Long],
    student: String,
    enrollmentDate: LocalDate,
    qualification: Long,
    status: EnrollmentStatus,
    courseId: Long
)

// why are all case classes final? In Scala, case classes are often declared as final to prevent further subclassing. This is done for several reasons:
// 1. Immutability: Case classes are designed to be immutable data structures. By making them final, it ensures that their behavior cannot be altered through inheritance, which helps maintain their immutability guarantees.
// 2. Pattern Matching: Case classes are commonly used in pattern matching. Making them final ensures that the pattern matching behavior is predictable and consistent, as there won't be any unexpected subclasses that could introduce new cases.
// 3. Performance: Final classes can be optimized by the compiler, leading to better performance in certain scenarios. The compiler can make assumptions about the class hierarchy, which can lead to more efficient code generation.
// 4. Simplicity: Declaring case classes as final simplifies the class hierarchy and reduces complexity, making it easier to reason about the code and its behavior.

/** Read-only user projection returned to callers. */
final case class User(
    id: Long,
    fullName: String,
    email: String,
    phoneNumber: String,
    status: UserType
)
