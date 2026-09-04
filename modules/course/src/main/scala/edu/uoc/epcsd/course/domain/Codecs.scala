package edu.uoc.epcsd.course.domain

// what is circe? Circe is a JSON library for Scala that provides functionality for encoding and decoding JSON data. It allows you to convert Scala objects to JSON and vice versa, making it easier to work with JSON in Scala applications. Circe uses type classes and functional programming concepts to provide a flexible and type-safe way to handle JSON serialization and deserialization.
// 
import io.circe.{Codec, Decoder, Encoder}

/** Helpers for the enum wire format. All JSON/DB naming lives here so the contract can be pinned
  *  by tests without scattering config through the codebase.
  */
object Codecs:
  /** Turn a camelCase Scala constructor name into the legacy UPPER_SNAKE wire value.
    *  e.g. `EnrollmentOpen` -> `ENROLLMENT_OPEN`, `Draft` -> `DRAFT`.
    */
  def toUpperSnakeCase(base: String): String =
    val out = new StringBuilder
    base.foreach { ch =>
      if ch.isUpper then
        if out.nonEmpty then out.append('_')
        out.append(ch)
      else out.append(ch.toUpper)
    }
    out.toString

  /** Reverse of `toUpperSnakeCase`: `ENROLLMENT_OPEN` -> `EnrollmentOpen`. */
  def fromUpperSnakeCase(s: String): String =
    s.split('_').map(_.toLowerCase.capitalize).mkString

  /** Precise Codec for an enum: idiomatic camelCase cases in Scala, UPPER_SNAKE on the wire,
    *  exactly matching the legacy contract. No reliance on generic derivation config.
    */
  def upperSnakeCodec[E](values: Array[E]): Codec[E] =
    val byWire = values.map(v => toUpperSnakeCase(v.toString) -> v).toMap
    Codec.from(
      Decoder.decodeString.emap(s => byWire.get(s).toRight(s"Unknown enum value: $s")),
      Encoder.encodeString.contramap(v => toUpperSnakeCase(v.toString))
    )

