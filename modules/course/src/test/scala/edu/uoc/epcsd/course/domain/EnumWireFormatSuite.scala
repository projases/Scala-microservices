package edu.uoc.epcsd.course.domain

import io.circe.syntax.*
import io.circe.parser.decode
import munit.FunSuite

/** Pins the enum JSON contract to the legacy UPPER_SNAKE wire format so it cannot drift. */
class EnumWireFormatSuite extends FunSuite:

  private val courseCases: List[(CourseStatus, String)] = List(
    CourseStatus.Draft            -> "DRAFT",
    CourseStatus.EnrollmentOpen   -> "ENROLLMENT_OPEN",
    CourseStatus.Active           -> "ACTIVE",
    CourseStatus.PendingClosure   -> "PENDING_CLOSURE",
    CourseStatus.Closed           -> "CLOSED"
  )

  private val enrollmentCases: List[(EnrollmentStatus, String)] = List(
    EnrollmentStatus.Active   -> "ACTIVE",
    EnrollmentStatus.Graded   -> "GRADED",
    EnrollmentStatus.Closed   -> "CLOSED"
  )

  private val userTypeCases: List[(UserType, String)] = List(
    UserType.Student     -> "STUDENT",
    UserType.Admin       -> "ADMIN",
    UserType.Instructor  -> "INSTRUCTOR"
  )

  test("CourseStatus encodes to UPPER_SNAKE wire values") {
    courseCases.foreach { case (v, wire) =>
      assertEquals(v.asJson.noSpaces, s"\"$wire\"")
    }
  }

  test("CourseStatus decodes from UPPER_SNAKE wire values") {
    courseCases.foreach { case (v, wire) =>
      val expected: Either[io.circe.Error, CourseStatus] = Right(v)
      assertEquals(decode[CourseStatus](s"\"$wire\""), expected)
    }
  }

  test("EnrollmentStatus encodes to UPPER_SNAKE wire values") {
    enrollmentCases.foreach { case (v, wire) =>
      assertEquals(v.asJson.noSpaces, s"\"$wire\"")
    }
  }

  test("EnrollmentStatus decodes from UPPER_SNAKE wire values") {
    enrollmentCases.foreach { case (v, wire) =>
      val expected: Either[io.circe.Error, EnrollmentStatus] = Right(v)
      assertEquals(decode[EnrollmentStatus](s"\"$wire\""), expected)
    }
  }

  test("UserType encodes to UPPER_SNAKE wire values") {
    userTypeCases.foreach { case (v, wire) =>
      assertEquals(v.asJson.noSpaces, s"\"$wire\"")
    }
  }

  test("UserType decodes from UPPER_SNAKE wire values") {
    userTypeCases.foreach { case (v, wire) =>
      val expected: Either[io.circe.Error, UserType] = Right(v)
      assertEquals(decode[UserType](s"\"$wire\""), expected)
    }
  }

  test("unknown wire values fail to decode") {
    assert(decode[CourseStatus]("\"NOT_A_STATUS\"").isLeft)
  }