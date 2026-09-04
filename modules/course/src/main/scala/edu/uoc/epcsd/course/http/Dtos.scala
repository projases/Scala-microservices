package edu.uoc.epcsd.course.http

import java.time.LocalDate

import io.circe.{Codec, Decoder, Encoder}
import io.circe.generic.semiauto.deriveCodec

import edu.uoc.epcsd.course.service.{CreateCourse, CourseRequestUpdate}
import edu.uoc.epcsd.course.domain.{Course, CourseStatus, Enrollment, EnrollmentStatus, User, UserType}

/** The domain models are serialised to JSON responses. circe's default derivation keeps
  *  camelCase field names (matching the legacy Jackson contract) and picks up each enum's own
  *  UPPER_SNAKE Codec automatically.
 */
// what is deriveCodec? deriveCodec is a method provided by the Circe library in Scala that automatically generates a Codec (encoder and decoder) for a case class or a sealed trait. It uses Scala's macro capabilities to inspect the structure of the class and generate the necessary code for JSON serialization and deserialization, allowing you to easily convert between Scala objects and JSON without manually writing the encoding and decoding logic.
// This line defines implicit Codec instances for the Course, Enrollment, and User case classes using Circe's deriveCodec method. This allows these classes to be automatically serialized to and deserialized from JSON, with field names in camelCase and enum values in UPPER_SNAKE format, matching the legacy contract.
given Codec[Course]     = deriveCodec
given Codec[Enrollment] = deriveCodec
given Codec[User]       = deriveCodec

/** Request body for POST /courses and PUT /courses/{id} (full course payload). */
final case class CourseRequest(
    instructor: String,
    title: String,
    description: String,
    enrollmentStartDate: LocalDate,
    enrollmentEndDate: LocalDate,
    mode: String,
    price: Long,
    objectives: String,
    methology: String,
    duration: Long,
    language: String,
    location: String
)

object CourseRequest:
  given Codec[CourseRequest] = deriveCodec

  def toCreate(r: CourseRequest): CreateCourse =
    CreateCourse(
      r.instructor, r.title, r.description,
      r.enrollmentStartDate, r.enrollmentEndDate,
      r.mode, r.price, r.objectives, r.methology,
      r.duration, r.language, r.location
    )

  def toUpdate(r: CourseRequest): CourseRequestUpdate =
    CourseRequestUpdate(
      r.title, r.description, r.mode, r.objectives, r.methology, r.duration, r.language
    )

/** Request body for PATCH /courses/{id}/openEnrollment. */
final case class OpenEnrollmentRequest(
    enrollmentStartDate: LocalDate,
    enrollmentEndDate: LocalDate
)

object OpenEnrollmentRequest:
  given Codec[OpenEnrollmentRequest] = deriveCodec

/** Request body for POST /courses/{id}/enrollments. */
final case class UserEmail(email: String)

object UserEmail:
  given Codec[UserEmail] = deriveCodec

/** LocalDate JSON encoding: ISO yyyy-MM-dd (e.g. "2025-01-11"), matching legacy Date JSON. */
given Codec[LocalDate] = Codec.from(
  Decoder.decodeString.emap(s => try Right(LocalDate.parse(s)) catch case _: Throwable => Left(s"Invalid date: $s")),
  Encoder.encodeString.contramap[LocalDate](_.toString)
)
