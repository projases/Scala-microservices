package edu.uoc.epcsd.course.service

import java.time.LocalDate

/** Plain application request types consumed by the pure service layer.
  *  They are deliberately framework-agnostic (no circe/http4s types), so the service has no
  *  dependency on the HTTP layer. The HTTP layer decodes JSON into these.
  */

final case class CreateCourse(
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

final case class CourseRequestUpdate(
    title: String,
    description: String,
    mode: String,
    objectives: String,
    methology: String,
    duration: Long,
    language: String
)
