package edu.uoc.epcsd.microcredential.domain

import java.time.Instant
import io.circe.Codec

/** Lifecycle of a microcredential. */
enum MicrocredentialStatus:
  case Requested, Granted, Rejected

object MicrocredentialStatus:
  given Codec[MicrocredentialStatus] = Codecs.upperSnakeCodec(values)

/** An unsaved microcredential request draft: no `id` yet (the DB assigns one on `create`). */
final case class NewMicrocredential(
    submitDate: Instant,
    assignmentDate: Option[Instant],
    status: MicrocredentialStatus,
    content: String,
    enrollment: Long
)

/** A persisted microcredential. */
final case class Microcredential(
    id: Long,
    submitDate: Instant,
    assignmentDate: Option[Instant],
    status: MicrocredentialStatus,
    content: String,
    enrollment: Long
)

object Microcredential:
  def request(enrollment: Long, now: Instant): NewMicrocredential =
    NewMicrocredential(now, None, MicrocredentialStatus.Requested, "", enrollment)

  /** Attach the DB-generated id to a request draft. */
  def fromNewMicrocredential(id: Long, newMicrocredential: NewMicrocredential): Microcredential =
    Microcredential(
      id = id,
      submitDate = newMicrocredential.submitDate,
      assignmentDate = newMicrocredential.assignmentDate,
      status = newMicrocredential.status,
      content = newMicrocredential.content,
      enrollment = newMicrocredential.enrollment
    )

/** Enrollment details as returned by the course service. */
final case class EnrollmentResponse(
    id: Long,
    student: String,
    courseId: Long
)