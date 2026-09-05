package edu.uoc.epcsd.microcredential.domain

import java.time.Instant
import io.circe.Codec

/** Lifecycle of a microcredential. */
enum MicrocredentialStatus:
  case Requested, Granted, Rejected

object MicrocredentialStatus:
  given Codec[MicrocredentialStatus] = Codecs.upperSnakeCodec(values)

final case class Microcredential(
    id: Option[Long],
    submitDate: Instant,
    assignmentDate: Option[Instant],
    status: MicrocredentialStatus,
    content: String,
    enrollment: Long
)

object Microcredential:
  def request(enrollment: Long, now: Instant): Microcredential =
    Microcredential(None, now, None, MicrocredentialStatus.Requested, "", enrollment)

/** Enrollment details as returned by the course service. */
final case class EnrollmentResponse(
    id: Long,
    student: String,
    courseId: Long
)
