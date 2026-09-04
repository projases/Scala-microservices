package edu.uoc.epcsd.course.infrastructure.user

import cats.effect.Concurrent
import cats.syntax.all.*
import org.http4s.*
import org.http4s.circe.CirceEntityDecoder.given
import org.http4s.client.Client
import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec

import edu.uoc.epcsd.course.domain.{User, UserType, UserService}

/** Response shape returned by the User service's GET /users/byEmail/{email}. */
final case class GetUserResponse(
    id: Long,
    fullName: String,
    email: String,
    phoneNumber: String,
    `type`: UserType
)

object GetUserResponse:
  given Codec[GetUserResponse] = deriveCodec
  def toDomain(r: GetUserResponse): User =
    User(r.id, r.fullName, r.email, r.phoneNumber, r.`type`)

/** http4s client interpreter for the User service. */
class UserClient[F[_]: Concurrent](client: Client[F], baseUrl: String) extends UserService[F]:

  def getUserByEmail(email: String): F[Option[User]] =
    val req = Request[F](Method.GET, Uri.unsafeFromString(s"$baseUrl/users/byEmail/$email"))
    client.run(req).use { resp =>
      resp.status match
        case Status.Ok       => resp.as[GetUserResponse].map(r => Some(GetUserResponse.toDomain(r)))
        case Status.NotFound => Option.empty[User].pure
        case _               => Option.empty[User].pure
    }

// what is traverseFilter? It is a method in cats that allows you to traverse a data structure while filtering out elements based on a predicate. It combines the functionality of traverse and filter, allowing you to apply a function to each element of the data structure and return a new data structure containing only the elements that satisfy the predicate.
  // def getUsersByEmails(emails: List[String]): F[List[User]] =
    // emails.parTraverseFilter(getUserByEmail).map(_.flatten)

  def isInstructor(email: String): F[Boolean] =
    getUserByEmail(email).map(_.exists(_.status == UserType.Instructor))
