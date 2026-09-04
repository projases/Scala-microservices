package edu.uoc.epcsd.user.http

import java.time.LocalDate

import cats.effect.Async
import cats.syntax.all.*
import io.circe.generic.semiauto.deriveCodec
import org.http4s.HttpRoutes
import sttp.model.StatusCode
import sttp.tapir.*
import sttp.tapir.generic.auto.*
import sttp.tapir.json.circe.*
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.http4s.Http4sServerInterpreter

import edu.uoc.epcsd.user.domain.*
import edu.uoc.epcsd.user.service.UserService

/** HTTP layer built on tapir `PublicEndpoint`s so the OpenAPI v3 document (`/v3/api-docs`) is
  *  derived from the same definitions that serve requests — no drift between spec and code.
  */
object Routes:
  import Dtos.given

  // ---- Error model (documented as the possible OpenAPI error responses) ----

  sealed trait ApiError
  object ApiError:
    final case class NotFound(message: String)   extends ApiError
    final case class BadRequest(message: String) extends ApiError
    final case class BadGateway(message: String) extends ApiError

  given io.circe.Codec[ApiError.NotFound]   = deriveCodec
  given io.circe.Codec[ApiError.BadRequest] = deriveCodec
  given io.circe.Codec[ApiError.BadGateway] = deriveCodec

  private def fromDomain(err: UserError): ApiError =
    err match
      case UserError.UserNotFound(_) | UserError.UserByEmailNotFound(_) | UserError.AlertNotFound(_) =>
        ApiError.NotFound(err.message)
      case UserError.UserDoesNotExist(_) | UserError.ProductDoesNotExist(_) =>
        ApiError.BadRequest(err.message)
      case UserError.ProductServiceUnavailable(_) =>
        ApiError.BadGateway(err.message)

  private val errorOut: EndpointOutput[ApiError] =
    oneOf[ApiError](
      oneOfVariantFromMatchType[ApiError.NotFound](StatusCode.NotFound, jsonBody[ApiError.NotFound]),
      oneOfVariantFromMatchType[ApiError.BadRequest](StatusCode.BadRequest, jsonBody[ApiError.BadRequest]),
      oneOfVariantFromMatchType[ApiError.BadGateway](StatusCode.BadGateway, jsonBody[ApiError.BadGateway])
    )

  // ---- LocalDate codec for query parameters ----

  private given Codec[String, LocalDate, CodecFormat.TextPlain] =
    sttp.tapir.Codec.string.mapDecode { s =>
      Either.catchNonFatal(LocalDate.parse(s)) match
        case Right(d) => DecodeResult.Value(d)
        case Left(e)  => DecodeResult.Error(s, e)
    }(_.toString)

  // ---- Public endpoints (used for serving and OpenAPI derivation) ----

  private val users: PublicEndpoint[Unit, ApiError, List[GetUserResponse], Any] =
    endpoint.get.in("users").errorOut(errorOut).out(jsonBody[List[GetUserResponse]])

  private val userByEmail: PublicEndpoint[String, ApiError, GetUserResponse, Any] =
    endpoint.get.in("users" / "byEmail" / path[String]("email")).errorOut(errorOut).out(jsonBody[GetUserResponse])

  private val usersToAlert: PublicEndpoint[(Long, LocalDate), ApiError, List[GetUserResponse], Any] =
    endpoint.get
      .in("users" / "toAlert")
      .in(query[Long]("productId").and(query[LocalDate]("availableOnDate")))
      .errorOut(errorOut)
      .out(jsonBody[List[GetUserResponse]])

  private val userById: PublicEndpoint[Long, ApiError, GetUserResponse, Any] =
    endpoint.get.in("users" / path[Long]("userId")).errorOut(errorOut).out(jsonBody[GetUserResponse])

  private val createUser: PublicEndpoint[CreateUserRequest, ApiError, Long, Any] =
    endpoint.post
      .in("users")
      .in(jsonBody[CreateUserRequest])
      .errorOut(errorOut)
      .out(statusCode(StatusCode.Created))
      .out(jsonBody[Long].description("created user id"))

  private val alerts: PublicEndpoint[Unit, ApiError, List[Alert], Any] =
    endpoint.get.in("alerts").errorOut(errorOut).out(jsonBody[List[Alert]])

  private val alertsByProductAndDate: PublicEndpoint[(Long, LocalDate), ApiError, List[Alert], Any] =
    endpoint.get
      .in("alerts" / "byProductAndDate")
      .in(query[Long]("productId").and(query[LocalDate]("availableOnDate")))
      .errorOut(errorOut)
      .out(jsonBody[List[Alert]])

  private val alertsByUserAndInterval: PublicEndpoint[(Long, LocalDate, LocalDate), ApiError, List[Alert], Any] =
    endpoint.get
      .in("alerts" / "byUserAndInterval")
      .in(query[Long]("userId").and(query[LocalDate]("fromDate")).and(query[LocalDate]("toDate")))
      .errorOut(errorOut)
      .out(jsonBody[List[Alert]])

  private val alertById: PublicEndpoint[Long, ApiError, Alert, Any] =
    endpoint.get.in("alerts" / path[Long]("alertId")).errorOut(errorOut).out(jsonBody[Alert])

  private val createAlert: PublicEndpoint[CreateAlertRequest, ApiError, Long, Any] =
    endpoint.post
      .in("alerts")
      .in(jsonBody[CreateAlertRequest])
      .errorOut(errorOut)
      .out(statusCode(StatusCode.Created))
      .out(jsonBody[Long].description("created alert id"))

  /** The set of endpoints published in the OpenAPI document. */
  val all: List[AnyEndpoint] = List(
    users,
    userByEmail,
    usersToAlert,
    userById,
    createUser,
    alerts,
    alertsByProductAndDate,
    alertsByUserAndInterval,
    alertById,
    createAlert
  )

  // ---- Server endpoints (wire endpoints to the service) ----

  private def serverEndpoints[F[_]: Async](svc: UserService[F]): List[ServerEndpoint[Any, F]] =
    List(
      users.serverLogicSuccess(_ => svc.findAllUsers),
      userByEmail.serverLogic { email =>
        svc.getUserByEmail(email).map {
          case Some(u) => Right(u)
          case None    => Left(ApiError.NotFound(s"User with email $email not found"))
        }
      },
      usersToAlert.serverLogicSuccess { case (pid, date) => svc.getUsersToAlert(pid, date) },
      userById.serverLogic { id =>
        svc.getUserById(id).map {
          case Some(u) => Right(u)
          case None    => Left(ApiError.NotFound(s"User with id $id not found"))
        }
      },
      createUser.serverLogic { req =>
        svc.createUser(req).value.map(_.leftMap(fromDomain))
      },
      alerts.serverLogicSuccess(_ => svc.findAllAlerts),
      alertsByProductAndDate.serverLogicSuccess { case (pid, date) => svc.findAlertsByProductAndDate(pid, date) },
      alertsByUserAndInterval.serverLogicSuccess { case (uid, from, to) => svc.findAlertsByUserAndInterval(uid, from, to) },
      alertById.serverLogic { id =>
        svc.getAlertById(id).map {
          case Some(a) => Right(a)
          case None    => Left(ApiError.NotFound(s"Alert with id $id not found"))
        }
      },
      createAlert.serverLogic { req =>
        svc.createAlert(req).value.map(_.leftMap(fromDomain))
      }
    )

  def apply[F[_]: Async](svc: UserService[F]): HttpRoutes[F] =
    Http4sServerInterpreter[F]().toRoutes(serverEndpoints(svc))
