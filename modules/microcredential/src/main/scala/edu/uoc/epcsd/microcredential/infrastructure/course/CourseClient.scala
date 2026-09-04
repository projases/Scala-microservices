package edu.uoc.epcsd.microcredential.infrastructure.course

import cats.data.EitherT
import cats.effect.Concurrent
import cats.syntax.all.*
import io.circe.generic.semiauto.deriveCodec
import io.circe.Codec
import org.http4s.*
import org.http4s.circe.CirceEntityDecoder.given
import org.http4s.client.Client

import edu.uoc.epcsd.microcredential.domain.{CourseService, Eff, EnrollmentResponse, MicrocredentialError}

object CourseClient:
  given Codec[EnrollmentResponse] = deriveCodec

/** http4s client interpreter for the Course service.
  *
  *  Errors (connection failures / non-2xx) are folded into `CourseServiceUnavailable` on the
  *  `Eff` channel rather than raised as raw exceptions.
  */
class CourseClient[F[_]: Concurrent](client: Client[F], baseUrl: String) extends CourseService[F]:
  import CourseClient.given

  def getCourseEnrollments(courseId: Long): Eff[F, List[EnrollmentResponse]] =
    val req = Request[F](Method.GET, Uri.unsafeFromString(s"$baseUrl/courses/$courseId/enrollments"))
    val attempt: F[Either[MicrocredentialError, List[EnrollmentResponse]]] =
      client
        .run(req)
        .use { resp =>
          resp.status match
            case Status.Ok          => resp.as[List[EnrollmentResponse]].map(Right(_))
            case s                  => Left(MicrocredentialError.CourseServiceUnavailable(s"status ${s.code}")).pure[F]
        }
        .handleError(e => Left(MicrocredentialError.CourseServiceUnavailable(e.getMessage)))
    EitherT(attempt)

  def getEnrollment(enrollmentId: Long): Eff[F, EnrollmentResponse] =
    val req = Request[F](Method.GET, Uri.unsafeFromString(s"$baseUrl/courses/enrollments/$enrollmentId"))
    val attempt: F[Either[MicrocredentialError, EnrollmentResponse]] =
      client
        .run(req)
        .use { resp =>
          resp.status match
            case Status.Ok          => resp.as[EnrollmentResponse].map(Right(_))
            case s                  => Left(MicrocredentialError.CourseServiceUnavailable(s"status ${s.code}")).pure[F]
        }
        .handleError(e => Left(MicrocredentialError.CourseServiceUnavailable(e.getMessage)))
    EitherT(attempt)
