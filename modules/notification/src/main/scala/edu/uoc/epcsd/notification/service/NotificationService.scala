package edu.uoc.epcsd.notification.service

import cats.effect.{Async, Clock}
import cats.effect.implicits.*
import cats.syntax.all.*
import org.typelevel.log4cats.slf4j.Slf4jLogger
import org.typelevel.log4cats.Logger

import edu.uoc.epcsd.notification.domain.{MicrocredentialMessage, ProductMessage}
import edu.uoc.epcsd.notification.infrastructure.user.{GetUserResponse, UserClient}
import edu.uoc.epcsd.notification.infrastructure.productcatalog.{GetProductResponse, ProductClient}

/** Turns asynchronous domain events into user notifications. In this demo the "email" is logged
  *  via log4cats, mirroring the legacy `NotificationServiceImpl` which only logged instead of
  *  actually sending mail. Each handler is resilient: a missing user/product or a service hiccup
  *  is logged and swallowed, so a single bad event never halts the consumer.
  */
trait NotificationService[F[_]]:
  def notifyProductAvailable(msg: ProductMessage): F[Unit]
  def notifyCredentialPending(msg: MicrocredentialMessage): F[Unit]
  def notifyCredentialGranted(msg: MicrocredentialMessage): F[Unit]
  def notifyCredentialRejected(msg: MicrocredentialMessage): F[Unit]

object NotificationService:
    // explain what does apply do? it creates an instance of NotificationService using the provided UserClient and ProductClient. It returns a new instance of the Live class, which implements the NotificationService trait and provides the actual logic for handling notifications. The apply method is a factory method that allows users to easily create a NotificationService without needing to know the details of the Live implementation.
  def apply[F[_]: Async](
      userClient: UserClient[F],
      productClient: ProductClient[F]
  ): NotificationService[F] =
    new Live[F](userClient, productClient)

  private final class Live[F[_]: Async](
      userClient: UserClient[F],
      productClient: ProductClient[F]
  ) extends NotificationService[F]:

    private given Logger[F] = Slf4jLogger.getLogger[F]

    override def notifyProductAvailable(msg: ProductMessage): F[Unit] =
      val effect = for
        product <- productClient.getProduct(msg.productId)
        _       <- Logger[F].info("notification.productAvailable")
        _       <- product match
                     case Some(p) => sendAvailabilityEmails(msg, p)
                     case None =>
                       Logger[F].warn(s"Product ${msg.productId} not found; skipping availability notification")
      yield ()
      effect.handleErrorWith(err =>
        Logger[F].error(err)(s"Failed to process product.unit_available for ${msg.productId}")
      )

    /** Fetches the users to alert and logs an email to each of them, in parallel. */
    private def sendAvailabilityEmails(msg: ProductMessage, product: GetProductResponse): F[Unit] =
      for
        today <- Clock[F].realTimeInstant
                   .map(_.atZone(java.time.ZoneId.systemDefault()).toLocalDate)
        users <- userClient.getUsersToAlert(msg.productId, today)
        _     <- users.parTraverse_(u => notifyProductEmail(u, product))
      yield ()

    private def notifyProductEmail(user: GetUserResponse, product: GetProductResponse): F[Unit] =
      Logger[F].info(
        s"Sending an email to user ${user.fullName} at \"${user.email}\"" +
          s" to notify new units available on product \"${product.name}\"."
      )

    override def notifyCredentialPending(msg: MicrocredentialMessage): F[Unit] =
      notifyCredential(msg, "is pending for approval")

    override def notifyCredentialGranted(msg: MicrocredentialMessage): F[Unit] =
      notifyCredential(msg, "has been granted")

    override def notifyCredentialRejected(msg: MicrocredentialMessage): F[Unit] =
      notifyCredential(msg, "has been rejected")

// what is verb? It is a string that describes the action taken on the microcredential, such as "is pending for approval", "has been granted", or "has been rejected". This string is used in the log message to inform the user about the status of their microcredential.
    private def notifyCredential(msg: MicrocredentialMessage, status: String): F[Unit] =
      val effect = for
        user <- userClient.getUserByEmail(msg.userEmail)
        _    <- user match
                  case Some(user) =>
                    Logger[F].info(
                      s"Sending an email to user ${user.fullName} at \"${user.email}\"" +
                        s" to notify that course with id ${msg.courseId} $status."
                    )
                  case None =>
                    Logger[F].warn(s"User ${msg.userEmail} not found; skipping microcredential notification")
      yield ()
      effect.handleErrorWith(err =>
        Logger[F].error(err)(s"Failed to process microcredential notification for ${msg.microcredentialId}")
      )
