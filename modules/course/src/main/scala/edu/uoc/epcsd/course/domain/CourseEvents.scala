package edu.uoc.epcsd.course.domain

import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec

/** Domain event emitted when a course is closed. Pure data — no framework types.
  *
  *  This is where the service turns a state change into an async, fire-and-forget notification,
  *  the functional replacement for the legacy Kafka/notification leg. Publishing is decoupled
  *  (see `infrastructure.events`), so the event survives a busy or down broker.
  */
final case class CourseClosed(courseId: Long, title: String)

object CourseClosed:
  given Codec[CourseClosed] = deriveCodec[CourseClosed]