package edu.uoc.epcsd.microcredential.http

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

import edu.uoc.epcsd.microcredential.domain.{Microcredential, MicrocredentialError}
import edu.uoc.epcsd.microcredential.service.MicrocredentialService

/** HTTP layer built on tapir `PublicEndpoint`s so the OpenAPI v3 document (`/v3/api-docs`) is
  *  derived from the same definitions that serve requests — no drift between spec and code.
  */
object Routes:
  // ---- Error model (documented as the possible OpenAPI error responses) ----

  sealed trait ApiError
  object ApiError:
    final case class NotFound(message: String)   extends ApiError
    final case class BadRequest(message: String) extends ApiError
    final case class BadGateway(message: String) extends ApiError

  given io.circe.Codec[ApiError.NotFound]   = deriveCodec
  given io.circe.Codec[ApiError.BadRequest] = deriveCodec
  given io.circe.Codec[ApiError.BadGateway] = deriveCodec

  private def fromDomain(err: MicrocredentialError): ApiError =
    err match
      case MicrocredentialError.NotFound(_) | MicrocredentialError.NoEnrollments(_) =>
        ApiError.NotFound(err.message)
      case MicrocredentialError.InvalidState(_, _) =>
        ApiError.BadRequest(err.message)
      case MicrocredentialError.CourseServiceUnavailable(_) =>
        ApiError.BadGateway(err.message)

  private val errorOut: EndpointOutput[ApiError] =
    oneOf[ApiError](
      oneOfVariantFromMatchType[ApiError.NotFound](StatusCode.NotFound, jsonBody[ApiError.NotFound]),
      oneOfVariantFromMatchType[ApiError.BadRequest](StatusCode.BadRequest, jsonBody[ApiError.BadRequest]),
      oneOfVariantFromMatchType[ApiError.BadGateway](StatusCode.BadGateway, jsonBody[ApiError.BadGateway])
    )

  // ---- Public endpoints (used for serving and OpenAPI derivation) ----

  private val pending: PublicEndpoint[Unit, ApiError, List[Microcredential], Any] =
    endpoint.get.in("microcredentials" / "pending").errorOut(errorOut).out(jsonBody[List[Microcredential]])

  private val byId: PublicEndpoint[Long, ApiError, Microcredential, Any] =
    endpoint.get.in("microcredentials" / path[Long]("microcredentialId")).errorOut(errorOut).out(jsonBody[Microcredential])

  private val approve: PublicEndpoint[Long, ApiError, Unit, Any] =
    endpoint.patch.in("microcredentials" / path[Long]("microcredentialId") / "approve").errorOut(errorOut)

  private val reject: PublicEndpoint[Long, ApiError, Unit, Any] =
    endpoint.patch.in("microcredentials" / path[Long]("microcredentialId") / "reject").errorOut(errorOut)

  private val create: PublicEndpoint[Long, ApiError, Unit, Any] =
    endpoint.post.in("microcredentials" / path[Long]("courseId") / "create").errorOut(errorOut)

  /** The set of endpoints published in the OpenAPI document. */
  val all: List[AnyEndpoint] = List(pending, byId, approve, reject, create)

  // ---- Server endpoints (wire endpoints to the service) ----

  private def serverEndpoints[F[_]: Async](svc: MicrocredentialService[F]): List[ServerEndpoint[Any, F]] =
    List(
      pending.serverLogicSuccess(_ => svc.getPendingMicrocredentialRequests),
      byId.serverLogic { id =>
        svc.getMicrocredentialById(id).map {
          case Some(m) => Right(m)
          case None    => Left(ApiError.NotFound(s"Microcredential with id $id not found"))
        }
      },
      approve.serverLogic(id => svc.approvePendingMicrocredential(id).value.map(_.leftMap(fromDomain))),
      reject.serverLogic(id => svc.rejectPendingMicrocredential(id).value.map(_.leftMap(fromDomain))),
      create.serverLogic(id => svc.requestCourseMicrocredentials(id).value.map(_.leftMap(fromDomain)))
    )

  def apply[F[_]: Async](svc: MicrocredentialService[F]): HttpRoutes[F] =
    Http4sServerInterpreter[F]().toRoutes(serverEndpoints(svc))
