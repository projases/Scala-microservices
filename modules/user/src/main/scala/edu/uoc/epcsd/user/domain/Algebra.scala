package edu.uoc.epcsd.user.domain

import java.time.LocalDate
import cats.Applicative
import cats.Functor
import cats.data.EitherT

type Eff[F[_], A] = EitherT[F, UserError, A]

object Eff:
  def liftF[F[_]: Applicative, A](fa: F[A]): Eff[F, A]       = EitherT.liftF(fa)
  def right[F[_]: Applicative, A](a: A): Eff[F, A]           = EitherT.rightT(a)
  def left[F[_]: Applicative, A](e: UserError): Eff[F, A]    = EitherT.leftT(e)
  def fromOption[F[_]: Applicative, A](o: Option[A], e: => UserError): Eff[F, A] =
    EitherT.fromOption(o, e)
  def fromOptionF[F[_]: Functor, A](fa: F[Option[A]], e: => UserError): Eff[F, A] =
    EitherT.fromOptionF(fa, e)
  def cond[F[_]: Applicative](c: Boolean, ok: => Unit, e: UserError): Eff[F, Unit] =
    if c then right(ok) else left(e)

/** Persistence for users + alerts. */
trait UserRepository[F[_]]:
  def findAllUsers: F[List[User]]
  def findUserById(id: Long): F[Option[User]]
  def findUserByEmail(email: String): F[Option[User]]
  def createUser(u: User): F[User]

trait AlertRepository[F[_]]:
  def findAllAlerts: F[List[Alert]]
  def findAlertById(id: Long): F[Option[Alert]]
  def findAlertsByProductAndDate(productId: Long, date: LocalDate): F[List[Alert]]
  def findAlertsByUserAndInterval(userId: Long, from: LocalDate, to: LocalDate): F[List[Alert]]
  def createAlert(a: Alert): F[Alert]

/** External Product catalog service (REST). */
trait ProductService[F[_]]:
  /** `true` if the product exists, `false` if not found. Service failures surface as `Eff` errors
    *  (e.g. `ProductServiceUnavailable`), mirroring the course module's client pattern.
    */
  def existsById(productId: Long): Eff[F, Boolean]
