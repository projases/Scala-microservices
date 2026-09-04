package edu.uoc.epcsd.user.domain

import java.time.LocalDate
import io.circe.Codec

enum UserType:
  case Student, Admin, Instructor

object UserType:
  given Codec[UserType] = Codecs.upperSnakeCodec(values)

final case class User(
    id: Long,
    fullName: String,
    email: String,
    password: String,
    phoneNumber: String,
    `type`: UserType = UserType.Student
)

object User:
  def fromRequest(req: CreateUserRequest): User =
    User(0L, req.fullName, req.email, req.password, req.phoneNumber, UserType.Student)

final case class Alert(
    id: Long,
    from: LocalDate,
    to: LocalDate,
    productId: Long,
    userId: Long
)

final case class CreateUserRequest(
    fullName: String,
    email: String,
    password: String,
    phoneNumber: String
)

final case class CreateAlertRequest(
    productId: Long,
    userId: Long,
    from: LocalDate,
    to: LocalDate
)

/** Public user projection, excluded `password` (fixes the legacy `GET /users` leak). */
final case class GetUserResponse(
    id: Long,
    fullName: String,
    email: String,
    phoneNumber: String,
    `type`: UserType
)

object GetUserResponse:
  def fromDomain(u: User): GetUserResponse =
    GetUserResponse(u.id, u.fullName, u.email, u.phoneNumber, u.`type`)
