package edu.uoc.epcsd.microcredential.http

import java.time.Instant

import org.http4s.Status
import io.circe.Json

import edu.uoc.epcsd.microcredential.domain.MicrocredentialError

/** Maps a domain error to a legacy-shaped error response. */
object ErrorHandler:

  def statusOf(e: MicrocredentialError): Status =
    e match
      case _: MicrocredentialError.NotFound              => Status.NotFound
      case _: MicrocredentialError.InvalidState          => Status.BadRequest
      case _: MicrocredentialError.NoEnrollments         => Status.NotFound
      case _: MicrocredentialError.CourseServiceUnavailable => Status.BadGateway

  def toJson(e: MicrocredentialError): Json =
    val status = statusOf(e)
    Json.obj(
      "timestamp" -> Json.fromString(Instant.now().toString),
      "status"    -> Json.fromInt(status.code),
      "error"     -> Json.fromString(status.reason),
      "message"   -> Json.fromString(e.message)
    )