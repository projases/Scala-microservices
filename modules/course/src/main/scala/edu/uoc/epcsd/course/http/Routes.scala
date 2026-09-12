package edu.uoc.epcsd.course.http

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

import edu.uoc.epcsd.course.domain.{Course, CourseError, CourseStatus, Enrollment, User}
import edu.uoc.epcsd.course.service.CourseService

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

  // Explicit breakpoint schemas: auto-derivation needs these to chain the `CourseStatus` enum
  // (and the `Course` -> `Enrollment` reference). Everything else derives automatically.
  given sttp.tapir.Schema[CourseStatus] = sttp.tapir.Schema.derived
  given sttp.tapir.Schema[Enrollment]   = sttp.tapir.Schema.derived


  private def fromDomain(err: CourseError): ApiError =
    err match
      case CourseError.CourseNotFound(_) | CourseError.EnrollmentNotFound(_) | CourseError.UserNotFound(_, _) =>
        ApiError.NotFound(err.message)
      case CourseError.InstructorNotFound(_) | CourseError.DuplicateCourseTitle(_) |
          CourseError.InvalidState(_, _) | CourseError.InvalidEnrollmentDates(_, _) |
          CourseError.EnrollmentsNotGraded(_) =>
        ApiError.BadRequest(err.message)
      case CourseError.UserServiceUnavailable(_) | CourseError.MicrocredentialServiceUnavailable(_) =>
        ApiError.BadGateway(err.message)

  private val errorOut: EndpointOutput[ApiError] =
    oneOf[ApiError](
      oneOfVariantFromMatchType[ApiError.NotFound](StatusCode.NotFound, jsonBody[ApiError.NotFound]),
      oneOfVariantFromMatchType[ApiError.BadRequest](StatusCode.BadRequest, jsonBody[ApiError.BadRequest]),
      oneOfVariantFromMatchType[ApiError.BadGateway](StatusCode.BadGateway, jsonBody[ApiError.BadGateway])
    )

  // ---- Public endpoints (used for serving and OpenAPI derivation) ----
  // 
// Types on PublicEndpoint[I, E, O, R]:
// I – input type (decoded from path/query/body).

// E – error type (here ApiError).

// O – output (success response body/type).

// R – requirements .
  private val courseById: PublicEndpoint[Long, ApiError, Course, Any] =
    endpoint.get.in("courses" / path[Long]("courseId")).errorOut(errorOut).out(jsonBody[Course])

  private val courses: PublicEndpoint[Unit, ApiError, List[Course], Any] =
    endpoint.get.in("courses").errorOut(errorOut).out(jsonBody[List[Course]])

  private val enrollmentsByCourse: PublicEndpoint[Long, ApiError, List[Enrollment], Any] =
    endpoint.get.in("courses" / path[Long]("courseId") / "enrollments").errorOut(errorOut).out(jsonBody[List[Enrollment]])

  private val enrollmentById: PublicEndpoint[Long, ApiError, Enrollment, Any] =
    endpoint.get.in("courses" / "enrollments" / path[Long]("enrollmentId")).errorOut(errorOut).out(jsonBody[Enrollment])

  private val students: PublicEndpoint[Long, ApiError, List[User], Any] =
    endpoint.get.in("courses" / path[Long]("courseId") / "students").errorOut(errorOut).out(jsonBody[List[User]])

  private val createCourse: PublicEndpoint[CourseRequest, ApiError, Long, Any] =
    endpoint.post
      .in("courses")
      .in(jsonBody[CourseRequest])
      .errorOut(errorOut)
      .out(statusCode(StatusCode.Created))
      .out(jsonBody[Long].description("created course id"))

  private val modifyCourse: PublicEndpoint[(Long, CourseRequest), ApiError, Unit, Any] =
    endpoint.put
      .in("courses" / path[Long]("courseId"))
      .in(jsonBody[CourseRequest])
      .errorOut(errorOut)

  private val openEnrollment: PublicEndpoint[(Long, OpenEnrollmentRequest), ApiError, Unit, Any] =
    endpoint.patch
      .in("courses" / path[Long]("courseId") / "openEnrollment")
      .in(jsonBody[OpenEnrollmentRequest])
      .errorOut(errorOut)

  private val closeEnrollment: PublicEndpoint[Long, ApiError, Unit, Any] =
    endpoint.patch.in("courses" / path[Long]("courseId") / "closeEnrollment").errorOut(errorOut)

  private val enroll: PublicEndpoint[(Long, UserEmail), ApiError, Unit, Any] =
    endpoint.post
      .in("courses" / path[Long]("courseId") / "enrollments")
      .in(jsonBody[UserEmail])
      .errorOut(errorOut)
      .out(statusCode(StatusCode.Created))

  private val closeGradeReports: PublicEndpoint[Long, ApiError, Unit, Any] =
    endpoint.patch.in("courses" / path[Long]("courseId") / "closeGradeReports").errorOut(errorOut)

  private val closeCourse: PublicEndpoint[Long, ApiError, Unit, Any] =
    endpoint.patch.in("courses" / path[Long]("courseId") / "closeCourse").errorOut(errorOut)

  /** The set of endpoints published in the OpenAPI document. */
  val all: List[AnyEndpoint] = List(
    courseById,
    courses,
    enrollmentsByCourse,
    enrollmentById,
    students,
    createCourse,
    modifyCourse,
    openEnrollment,
    closeEnrollment,
    enroll,
    closeGradeReports,
    closeCourse
  )

  // ---- Server endpoints (wire endpoints to the service) ----

  private def serverEndpoints[F[_]: Async](svc: CourseService[F]): List[ServerEndpoint[Any, F]] =
    List(
      courseById.serverLogic { id =>
        svc.getCourseById(id).map {
          case Some(c) => Right(c)
          case None    => Left(ApiError.NotFound(s"Course with id $id not found"))
        }
      },
      courses.serverLogicSuccess(_ => svc.findCourses),
      enrollmentsByCourse.serverLogicSuccess(id => svc.getEnrollmentsByCourse(id)),
      enrollmentById.serverLogic { id =>
        svc.getEnrollmentById(id).map {
          case Some(e) => Right(e)
          case None    => Left(ApiError.NotFound(s"Enrollment with id $id not found"))
        }
      },
      students.serverLogicSuccess(id => svc.getEnrolledStudents(id)),
      createCourse.serverLogic { r =>
        svc.createCourse(CourseRequest.toCreate(r)).value.map(_.leftMap(fromDomain))
      },
      modifyCourse.serverLogic { in =>
        val (id, r) = in
        svc.modifyCourseDetails(id, CourseRequest.toUpdate(r)).value.map(_.leftMap(fromDomain))
      },
      openEnrollment.serverLogic { in =>
        val (id, r) = in
        svc.openEnrollment(id, r.enrollmentStartDate, r.enrollmentEndDate).value.map(_.leftMap(fromDomain))
      },
      closeEnrollment.serverLogic(id => svc.closeEnrollment(id).value.map(_.leftMap(fromDomain))),
      enroll.serverLogic { in =>
        val (id, e) = in
        svc.enrollInCourse(id, e.email).value.map(_.leftMap(fromDomain))
      },
      closeGradeReports.serverLogic(id => svc.closeGradeReports(id).value.map(_.leftMap(fromDomain))),
      closeCourse.serverLogic(id => svc.closeCourse(id).value.map(_.leftMap(fromDomain)))
    )

  def apply[F[_]: Async](svc: CourseService[F]): HttpRoutes[F] =
    Http4sServerInterpreter[F]().toRoutes(serverEndpoints(svc))
