package edu.uoc.epcsd.productcatalog

import cats.effect.{IO, IOApp}
import org.http4s.HttpRoutes
import org.http4s.dsl.io.*
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits.*
import com.comcast.ip4s.{Host, Port}

/** Minimal in-memory product catalog stub.
  *
  *  The user service validates new alerts against the catalog by calling
  *  `GET /products/{productId}` and treating 200 as "exists" and 404 as "does not exist"
  *  (see `ProductClient.existsById`). This stub reproduces that contract so `POST /alerts`
  *  can be exercised end-to-end in the portfolio demo without a real catalog database.
  *
  *  A deliberately small, fixed set of product ids is considered existing; everything else 404s.
  */
object Main extends IOApp.Simple:

  /** ids considered to exist in the catalog */
  private val existingProductIds: Set[Long] = Set(1L, 2L, 3L, 10L, 11L, 12L, 20L, 100L, 101L, 102L)

  private val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "products" / LongVar(id) =>
      if existingProductIds.contains(id) then Ok(s"""{"id":$id,"name":"Stub Product $id"}""")
      else NotFound(s"""{"message":"Product $id not found"}""")
  }

  def run: IO[Unit] =
    EmberServerBuilder
      .default[IO]
      .withHost(Host.fromString("0.0.0.0").get)
      .withPort(Port.fromInt(18081).get)
      .withHttpApp(routes.orNotFound)
      .build
      .useForever
