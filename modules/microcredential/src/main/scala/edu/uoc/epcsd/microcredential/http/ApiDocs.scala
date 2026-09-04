package edu.uoc.epcsd.microcredential.http

import cats.effect.Async
import cats.syntax.all.*
import io.circe.syntax.*
import org.http4s.HttpRoutes
import sttp.apispec.openapi.circe.*
import sttp.tapir.*
import sttp.tapir.docs.openapi.OpenAPIDocsInterpreter
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.http4s.Http4sServerInterpreter

object ApiDocs:
  val title   = "UOC Microcredential Service"
  val version = "v1"

  /** OpenAPI v3 document as JSON, derived from the same tapir endpoints that serve requests. */
  val json: String =
    OpenAPIDocsInterpreter().toOpenAPI(Routes.all, title, version).asJson.noSpaces

  private def docsEndpoint[F[_]: Async]: ServerEndpoint[Any, F] =
    endpoint.get.in("v3" / "api-docs").out(stringBody).serverLogicSuccess(_ => json.pure[F])

  def routes[F[_]: Async]: HttpRoutes[F] =
    Http4sServerInterpreter[F]().toRoutes(List(docsEndpoint))

  /** Prints the OpenAPI JSON to stdout, for generating the checked-in specification file:
    *  `sbt "microcredential/runMain edu.uoc.epcsd.microcredential.http.ApiDocsGen" > docs/microcredential-openapi.json`
    */
  def main(args: Array[String]): Unit = println(json)

object ApiDocsGen:
  def main(args: Array[String]): Unit = println(ApiDocs.json)
