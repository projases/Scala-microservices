package edu.uoc.epcsd.microcredential.domain

sealed trait MicrocredentialError extends Product with Serializable:
  def message: String

object MicrocredentialError:
  final case class NotFound(id: Long) extends MicrocredentialError:
    def message: String = s"Microcredential with id $id not found"

  final case class InvalidState(expected: String, actual: String) extends MicrocredentialError:
    def message: String = s"Invalid state: expected $expected, got $actual"

  final case class NoEnrollments(courseId: Long) extends MicrocredentialError:
    def message: String = s"No enrollments found for course $courseId"

  final case class CourseServiceUnavailable(cause: String) extends MicrocredentialError:
    def message: String = s"Course service unavailable: $cause"
