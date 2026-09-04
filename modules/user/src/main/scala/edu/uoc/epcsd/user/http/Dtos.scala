package edu.uoc.epcsd.user.http

import java.time.LocalDate

import cats.syntax.all.*
import io.circe.generic.semiauto.deriveCodec
import io.circe.{Codec, Decoder, Encoder}

import edu.uoc.epcsd.user.domain.*

/** Circe codecs for the HTTP wire format. Dates default to ISO `yyyy-MM-dd` (a deliberate,
  *  cleaner normalization of the legacy `dd-MM-yyyy` body format).
  */
object Dtos:
  private val localDateDecoder: Decoder[LocalDate] =
    io.circe.Decoder.decodeString.emap(s =>
      Either.catchNonFatal(LocalDate.parse(s)).leftMap(_.getMessage)
    )
  private val localDateEncoder: Encoder[LocalDate] =
    io.circe.Encoder.encodeString.contramap(_.toString)
  private given Codec[LocalDate] = Codec.from(localDateDecoder, localDateEncoder)

  given Codec[GetUserResponse]   = deriveCodec
  given Codec[CreateUserRequest] = deriveCodec
  given Codec[CreateAlertRequest] = deriveCodec
  given Codec[Alert]             = deriveCodec
  given Encoder[User]            = deriveCodec
