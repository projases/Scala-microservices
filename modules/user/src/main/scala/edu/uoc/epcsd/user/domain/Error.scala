package edu.uoc.epcsd.user.domain

/** Domain errors for the user service, modeled as a sealed ADT. */
sealed trait UserError extends Product with Serializable:
  def message: String

object UserError:
  final case class UserNotFound(id: Long) extends UserError:
    def message: String = s"User with id $id not found"

  final case class UserByEmailNotFound(email: String) extends UserError:
    def message: String = s"User with email $email not found"

  final case class AlertNotFound(id: Long) extends UserError:
    def message: String = s"Alert with id $id not found"

  final case class UserDoesNotExist(userId: Long) extends UserError:
    def message: String = s"The specified userId $userId does not exist."

  final case class ProductDoesNotExist(productId: Long) extends UserError:
    def message: String = s"The specified productId $productId does not exist."

  final case class ProductServiceUnavailable(cause: String) extends UserError:
    def message: String = s"Product service unavailable: $cause"
