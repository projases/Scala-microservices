package edu.uoc.epcsd.user.infrastructure.repository

import java.time.LocalDate

import cats.effect.MonadCancel
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import doobie.util.transactor.Transactor

import edu.uoc.epcsd.user.domain.*

object DoobieMappings:
  private def toEnumName(snake: String): String = Codecs.fromUpperSnakeCase(snake)
  given Meta[UserType] =
    Meta[String].timap(s => UserType.valueOf(toEnumName(s)))(c => Codecs.toUpperSnakeCase(c.toString))

import DoobieMappings.given

class DoobieUserRepository[F[_]](xa: Transactor[F])(using F: MonadCancel[F, Throwable])
    extends UserRepository[F]:

  private val selectColumns =
    fr"id, full_name, email, password, phone_number, type"

  private type UserRow = (Long, String, String, String, String, UserType)

  def findAllUsers: F[List[User]] =
    (fr"SELECT" ++ selectColumns ++ fr"FROM users")
      .query[UserRow]
      .map(toUser)
      .to[List]
      .transact(xa)

  def findUserById(id: Long): F[Option[User]] =
    (fr"SELECT" ++ selectColumns ++ fr"FROM users WHERE id = $id")
      .query[UserRow]
      .map(toUser)
      .option
      .transact(xa)

  def findUserByEmail(email: String): F[Option[User]] =
    (fr"SELECT" ++ selectColumns ++ fr"FROM users WHERE email = $email")
      .query[UserRow]
      .map(toUser)
      .option
      .transact(xa)

  def createUser(u: User): F[User] =
    sql"""INSERT INTO users (full_name, email, password, phone_number, type)
          VALUES (${u.fullName}, ${u.email}, ${u.password}, ${u.phoneNumber}, ${u.`type`})
       """.update.withUniqueGeneratedKeys[Long]("id").transact(xa).map(id => u.copy(id = id))

  private def toUser(t: UserRow): User =
    User(t._1, t._2, t._3, t._4, t._5, t._6)

class DoobieAlertRepository[F[_]](xa: Transactor[F])(using F: MonadCancel[F, Throwable])
    extends AlertRepository[F]:

  private val quoted = fr"""id, "from", "to", product_id, user_id"""
  private type AlertRow = (Long, LocalDate, LocalDate, Long, Long)

  def findAllAlerts: F[List[Alert]] =
    (fr"SELECT" ++ quoted ++ fr"FROM alert")
      .query[AlertRow]
      .map(toAlert)
      .to[List]
      .transact(xa)

  def findAlertById(id: Long): F[Option[Alert]] =
    (fr"SELECT" ++ quoted ++ fr"FROM alert WHERE id = $id")
      .query[AlertRow]
      .map(toAlert)
      .option
      .transact(xa)

  def findAlertsByProductAndDate(productId: Long, date: LocalDate): F[List[Alert]] =
    (fr"SELECT" ++ quoted ++
      fr"""FROM alert WHERE product_id = $productId AND "from" <= $date AND "to" >= $date""")
      .query[AlertRow]
      .map(toAlert)
      .to[List]
      .transact(xa)

  def findAlertsByUserAndInterval(userId: Long, from: LocalDate, to: LocalDate): F[List[Alert]] =
    (fr"SELECT" ++ quoted ++
      fr"""FROM alert WHERE user_id = $userId AND "from" <= $to AND "to" >= $from""")
      .query[AlertRow]
      .map(toAlert)
      .to[List]
      .transact(xa)

  def createAlert(a: Alert): F[Alert] =
    sql"""INSERT INTO alert ("from", "to", product_id, user_id)
          VALUES (${a.from}, ${a.to}, ${a.productId}, ${a.userId})
       """.update.withUniqueGeneratedKeys[Long]("id").transact(xa).map(id => a.copy(id = id))

  private def toAlert(t: AlertRow): Alert =
    Alert(t._1, t._2, t._3, t._4, t._5)
