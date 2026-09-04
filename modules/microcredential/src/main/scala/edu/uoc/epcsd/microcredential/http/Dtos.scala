package edu.uoc.epcsd.microcredential.http

import java.time.Instant

import io.circe.{Codec, Decoder, Encoder}
import io.circe.generic.semiauto.deriveCodec

import edu.uoc.epcsd.microcredential.domain.Microcredential

/** The domain model is serialised to JSON responses (camelCase fields, UPPER_SNAKE status). */
given Codec[Microcredential] = deriveCodec

/** Instant JSON encoding as ISO-8601 (e.g. "2025-01-11T09:30:00Z"). */
given Codec[Instant] = Codec.from(
  Decoder.decodeString.emap(s => try Right(Instant.parse(s)) catch case _: Throwable => Left(s"Invalid instant: $s")),
  Encoder.encodeString.contramap[Instant](_.toString)
)