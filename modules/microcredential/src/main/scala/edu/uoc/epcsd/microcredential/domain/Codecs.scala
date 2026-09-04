package edu.uoc.epcsd.microcredential.domain

import io.circe.{Codec, Decoder, Encoder}

object Codecs:
  def toUpperSnakeCase(base: String): String =
    val out = new StringBuilder
    base.foreach { ch =>
      if ch.isUpper then
        if out.nonEmpty then out.append('_')
        out.append(ch)
      else out.append(ch.toUpper)
    }
    out.toString

  def fromUpperSnakeCase(s: String): String =
    s.split('_').map(_.toLowerCase.capitalize).mkString

  def upperSnakeCodec[E](values: Array[E]): Codec[E] =
    val byWire = values.map(v => toUpperSnakeCase(v.toString) -> v).toMap
    Codec.from(
      Decoder.decodeString.emap(s => byWire.get(s).toRight(s"Unknown enum value: $s")),
      Encoder.encodeString.contramap(v => toUpperSnakeCase(v.toString))
    )
