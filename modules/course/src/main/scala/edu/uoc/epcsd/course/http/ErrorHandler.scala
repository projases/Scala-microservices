package edu.uoc.epcsd.course.http

import java.time.Instant

import org.http4s.Status
import io.circe.Json

import edu.uoc.epcsd.course.domain.CourseError

/** Maps a domain error to a legacy-shaped error response. */
object ErrorHandler:

  def statusOf(e: CourseError): Status =
    e match
      case _: CourseError.CourseNotFound       => Status.NotFound
      case _: CourseError.EnrollmentNotFound   => Status.NotFound
      case _: CourseError.UserNotFound         => Status.NotFound
      case _: CourseError.InstructorNotFound   => Status.BadRequest
      case _: CourseError.DuplicateCourseTitle => Status.BadRequest
      case _: CourseError.InvalidState         => Status.BadRequest
      case _: CourseError.EnrollmentsNotGraded => Status.BadRequest
      case _: CourseError.InvalidEnrollmentDates => Status.BadRequest
      case _: CourseError.UserServiceUnavailable => Status.BadGateway
      case _: CourseError.MicrocredentialServiceUnavailable => Status.BadGateway

  def toJson(e: CourseError): Json =
    val status = statusOf(e)
    Json.obj(
      "timestamp" -> Json.fromString(Instant.now().toString),
      "status"    -> Json.fromInt(status.code),
      "error"     -> Json.fromString(status.reason),
      "message"   -> Json.fromString(e.message)
    )
