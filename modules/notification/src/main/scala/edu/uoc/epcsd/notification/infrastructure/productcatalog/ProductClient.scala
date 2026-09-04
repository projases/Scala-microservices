package edu.uoc.epcsd.notification.infrastructure.productcatalog

import cats.effect.Async
import cats.syntax.all.*
import io.circe.Decoder
import io.circe.generic.semiauto.deriveDecoder
import org.http4s.circe.CirceEntityDecoder.given
import org.http4s.client.Client
import org.http4s.{Method, Request, Status, Uri}

/** Product details returned by the (stub) productcatalog service. */
final case class GetProductResponse(id: Long, name: String)

object GetProductResponse:
  given Decoder[GetProductResponse] = deriveDecoder

/** http4s client interpreter for the productcatalog service (GET /products/{id}). */
class ProductClient[F[_]: Async](client: Client[F], baseUrl: String):

  def getProduct(productId: Long): F[Option[GetProductResponse]] =
    val req = Request[F](Method.GET, Uri.unsafeFromString(s"$baseUrl/products/$productId"))
    client.run(req).use { resp =>
      resp.status match
        case Status.Ok       => resp.as[GetProductResponse].map(Some(_))
        case Status.NotFound => Option.empty[GetProductResponse].pure
        case _               => Option.empty[GetProductResponse].pure
    }
