package edu.uoc.epcsd.notification.domain

import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec

/** Async inbound message: a product has new units available. Produced by the productcatalog
  *  service on the `product.unit_available` routing key.
  */
final case class ProductMessage(productId: Long)

object ProductMessage:
  given Codec[ProductMessage] = deriveCodec

/** Async inbound message: a microcredential changed state. Produced by the microcredential
  *  service on `microcredential.pending` / `.approved` / `.rejected` routing keys.
  */
final case class MicrocredentialMessage(
    microcredentialId: Long,
    userEmail: String,
    courseId: Long,
    enrollment: Long
)

object MicrocredentialMessage:
  given Codec[MicrocredentialMessage] = deriveCodec
