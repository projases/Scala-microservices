package edu.uoc.epcsd.course.domain

import java.time.LocalDate

/** Domain errors, expressed as a sealed ADT instead of runtime exceptions. */
// what is sealed trait? A sealed trait in Scala is a trait that can only be extended in the same file where it is defined. This allows the compiler to know all possible subtypes of the trait, enabling exhaustive pattern matching and better type safety. It is often used to define algebraic data types (ADTs) where you want to represent a closed set of possible values or cases.
// What are product and serializable? In Scala, a Product is a trait that represents a tuple or a case class, which can hold multiple values. It provides methods to access the elements of the product. Serializable is a marker trait that indicates that instances of the class can be serialized, meaning they can be converted into a format that can be stored or transmitted and later reconstructed. Together, Product with Serializable is often used in case classes to indicate that they are both products (holding multiple values) and can be serialized.
sealed trait CourseError extends Product with Serializable:
    // why def message? The `message` method is defined in the `CourseError` trait to provide a human-readable description of the error. Each specific error case that extends `CourseError` implements this method to return a relevant error message. This allows for consistent error reporting and makes it easier to log or display error information to users or developers.
  def message: String

object CourseError:
  final case class CourseNotFound(courseId: Long) extends CourseError:
    def message: String = s"Course with id ${courseId} not found"

  final case class EnrollmentNotFound(enrollmentId: Long) extends CourseError:
    def message: String = s"Enrollment with id ${enrollmentId} not found"

  final case class UserNotFound(email: String, isInstructor: Boolean) extends CourseError:
    def message: String =
      if isInstructor then s"Instructor with email '${email}' not found"
      else s"User with email '${email}' not found"

  final case class InstructorNotFound(email: String) extends CourseError:
    def message: String = s"Instructor with email '${email}' not found"

  final case class DuplicateCourseTitle(title: String) extends CourseError:
    def message: String = s"Course title must be unique: '${title}'"

  final case class InvalidState(expected: String, actual: String) extends CourseError:
    def message: String = s"Invalid state for operation: expected '$expected', got '$actual'"

  final case class EnrollmentsNotGraded(courseId: Long) extends CourseError:
    def message: String = s"All enrollments must be GRADED before course $courseId can be closed"

  final case class InvalidEnrollmentDates(start: LocalDate, end: LocalDate) extends CourseError:
    def message: String = s"Invalid enrollment dates: start $start must not be after end $end"

  final case class UserServiceUnavailable(cause: String) extends CourseError:
    def message: String = s"User service unavailable: $cause"

  final case class MicrocredentialServiceUnavailable(cause: String) extends CourseError:
    def message: String = s"Microcredential service unavailable: $cause"
