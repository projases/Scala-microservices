package edu.uoc.epcsd.user.service

import java.time.LocalDate

import cats.Monad
import cats.Parallel
import cats.syntax.all.*

import edu.uoc.epcsd.user.domain.*
import edu.uoc.epcsd.user.domain.Eff.{cond, fromOptionF, liftF}

class UserService[F[_]: Monad: Parallel](
    userRepo: UserRepository[F],
    alertRepo: AlertRepository[F],
    productSvc: ProductService[F]
):

  // ---- Users ----

  def findAllUsers: F[List[GetUserResponse]] =
    userRepo.findAllUsers.map(_.map(GetUserResponse.fromDomain))

  def getUserById(id: Long): F[Option[GetUserResponse]] =
    userRepo.findUserById(id).map(_.map(GetUserResponse.fromDomain))

  def getUserByEmail(email: String): F[Option[GetUserResponse]] =
    userRepo.findUserByEmail(email).map(_.map(GetUserResponse.fromDomain))

  /** Users watching `productId` whose alert covers `date`, deduplicated. */
  def getUsersToAlert(productId: Long, date: LocalDate): F[List[GetUserResponse]] =
    for
      alerts <- alertRepo.findAlertsByProductAndDate(productId, date)
      users  <- alerts.parTraverseFilter(a => userRepo.findUserById(a.userId))
    yield users.map(GetUserResponse.fromDomain).distinctBy(_.id)

  def createUser(req: CreateUserRequest): Eff[F, Long] =
    liftF(userRepo.createUser(User.fromRequest(req))).map(_.id)

  // ---- Alerts ----

  def findAllAlerts: F[List[Alert]] =
    alertRepo.findAllAlerts

  def getAlertById(id: Long): F[Option[Alert]] =
    alertRepo.findAlertById(id)

  def findAlertsByProductAndDate(productId: Long, date: LocalDate): F[List[Alert]] =
    alertRepo.findAlertsByProductAndDate(productId, date)

  def findAlertsByUserAndInterval(userId: Long, from: LocalDate, to: LocalDate): F[List[Alert]] =
    alertRepo.findAlertsByUserAndInterval(userId, from, to)

  def createAlert(req: CreateAlertRequest): Eff[F, Long] =
    for
      _      <- fromOptionF(userRepo.findUserById(req.userId), UserError.UserDoesNotExist(req.userId))
      prod   <- productSvc.existsById(req.productId)
      _      <- cond(prod, (), UserError.ProductDoesNotExist(req.productId))
      a      <- liftF(alertRepo.createAlert(Alert(0L, req.from, req.to, req.productId, req.userId)))
    yield a.id
