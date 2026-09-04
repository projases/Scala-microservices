package edu.uoc.epcsd.notification.infrastructure.user

import java.time.LocalDate

import cats.effect.Async
import cats.syntax.all.*
import io.circe.Decoder
import io.circe.generic.semiauto.deriveDecoder
import org.http4s.circe.CirceEntityDecoder.given
import org.http4s.client.Client
import org.http4s.{Method, Request, Status, Uri}

/** Public user projection returned by the User service. `type` is decoded as a plain String
  *  (it is only used for logging here).
  */
final case class GetUserResponse(
    id: Long,
    fullName: String,
    email: String,
    phoneNumber: String,
    `type`: String
)

object GetUserResponse:
  given Decoder[GetUserResponse] = deriveDecoder

/** http4s client interpreter for the User service (GET /users/byEmail/{email} and
  *  GET /users/toAlert). Failures and missing users are folded into `Option`/empty so a single
  *  event never halts the consumer.
  */
class UserClient[F[_]: Async](client: Client[F], baseUrl: String):

  def getUserByEmail(email: String): F[Option[GetUserResponse]] =
    val req = Request[F](Method.GET, Uri.unsafeFromString(s"$baseUrl/users/byEmail/$email"))
    client.run(req).use { resp =>
      resp.status match
        case Status.Ok       => resp.as[GetUserResponse].map(Some(_))
        case Status.NotFound => Option.empty[GetUserResponse].pure
        case _               => Option.empty[GetUserResponse].pure
    }

  def getUsersToAlert(productId: Long, date: LocalDate): F[List[GetUserResponse]] =
    val uri = Uri
      .unsafeFromString(s"$baseUrl/users/toAlert")
      .withQueryParam("productId", productId)
      .withQueryParam("availableOnDate", date.toString)
    val req = Request[F](Method.GET, uri)
    client.run(req).use { resp =>
      resp.status match
        case Status.Ok       => resp.as[List[GetUserResponse]]
        case Status.NotFound => List.empty[GetUserResponse].pure
        case _               => List.empty[GetUserResponse].pure
    }
