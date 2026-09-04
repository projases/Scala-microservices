package edu.uoc.epcsd.user.infrastructure.product

import cats.data.EitherT
import cats.effect.Concurrent
import cats.syntax.all.*
import org.http4s.*
import org.http4s.client.Client

import edu.uoc.epcsd.user.domain.{Eff, ProductService, UserError}

/** http4s client interpreter for the Product catalog service.
  *
  *  `existsById` is an existence check expressed as an `Eff`: 200 => true, 404 => false, and any
  *  other status or a connection failure is folded into `ProductServiceUnavailable` (never raised
  *  as a raw exception), mirroring how the course module's `MicrocredentialClient` handles errors.
  */
class ProductClient[F[_]: Concurrent](client: Client[F], baseUrl: String) extends ProductService[F]:

  def existsById(productId: Long): Eff[F, Boolean] =
    val req = Request[F](Method.GET, Uri.unsafeFromString(s"$baseUrl/products/$productId"))
    val attempt: F[Either[UserError, Boolean]] =
      client
        .run(req)
        .use { resp =>
          resp.status.code match
            case 200 => Right(true).pure[F]
            case 404 => Right(false).pure[F]
            case s   => Left(UserError.ProductServiceUnavailable(s"unexpected status $s")).pure[F]
        }
        .handleError(e => Left(UserError.ProductServiceUnavailable(e.getMessage)))
    EitherT(attempt)
