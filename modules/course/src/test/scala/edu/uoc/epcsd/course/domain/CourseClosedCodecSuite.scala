package edu.uoc.epcsd.course.domain

import io.circe.parser.decode
import io.circe.syntax.*
import munit.FunSuite

/** Pins the CourseClosed event JSON contract (snake_case fields, the same circe encoding used
  *  both at the RabbitMQ publisher and by fs2-rabbit consumers).
  */
class CourseClosedCodecSuite extends FunSuite:

  test("CourseClosed encodes to JSON and decodes back") {
    val ev = CourseClosed(3L, "Night Photography")
    assertEquals(decode[CourseClosed](ev.asJson.noSpaces), Right(ev))
  }

  test("CourseClosed uses the default circe field names on the wire") {
    assertEquals(CourseClosed(3L, "Night").asJson.noSpaces, """{"courseId":3,"title":"Night"}""")
  }