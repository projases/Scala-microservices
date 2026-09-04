package edu.uoc.epcsd.user.http

import org.http4s.Status
import io.circe.Json
import io.circe.syntax.*

import edu.uoc.epcsd.user.domain.UserError

object ErrorHandler:

  def statusOf(err: UserError): Status =
    err match
      case UserError.UserNotFound(_) | UserError.UserByEmailNotFound(_) | UserError.AlertNotFound(_) =>
        Status.NotFound
      case UserError.UserDoesNotExist(_) | UserError.ProductDoesNotExist(_) =>
        Status.BadRequest
      case UserError.ProductServiceUnavailable(_) =>
        Status.BadGateway

  def toJson(err: UserError): Json =
    Json.obj("message" -> err.message.asJson)
