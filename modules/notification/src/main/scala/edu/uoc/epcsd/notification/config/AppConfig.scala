package edu.uoc.epcsd.notification.config

import scala.jdk.CollectionConverters.*

import com.typesafe.config.{Config, ConfigFactory}

final case class ServerConfig(port: Int, host: String)
final case class ConsumerConfig(baseUrl: String)
final case class UserServiceConfig(baseUrl: String)
final case class ProductServiceConfig(baseUrl: String)
final case class RetryConfig(maxAttempts: Int, baseDelayMillis: Long)

/** RabbitMQ connection + topology for one event stream (microcredential or product). Each stream
  *  has its own exchange and a set of routing keys (commands) the notification consumer binds.
  */
final case class RabbitStreamConfig(
    enabled: Boolean,
    host: String,
    port: Int,
    virtualHost: String,
    username: String,
    password: String,
    exchange: String,
    queue: String,
    routingKeys: List[String]
)

final case class AppConfig(
    server: ServerConfig,
    userService: UserServiceConfig,
    productService: ProductServiceConfig,
    retry: RetryConfig,
    microcredential: RabbitStreamConfig,
    product: RabbitStreamConfig
)

object AppConfig:
  def load: Either[String, AppConfig] =
    load(ConfigFactory.load())

  def load(config: Config): Either[String, AppConfig] =
    try
      val server = config.getConfig("server")
      val user = config.getConfig("userService")
      val product = config.getConfig("productService")
      val retry = config.getConfig("retry")
      Right(
        AppConfig(
          ServerConfig(server.getInt("port"), server.getString("host")),
          UserServiceConfig(user.getString("baseUrl")),
          ProductServiceConfig(product.getString("baseUrl")),
          RetryConfig(retry.getInt("maxAttempts"), retry.getLong("baseDelayMillis")),
          rabbitStream(config.getConfig("microcredential"),
            "microcredential.events", "notification.microcredential",
            List("microcredential.pending", "microcredential.approved", "microcredential.rejected")),
          rabbitStream(config.getConfig("product"),
            "product.events", "notification.product",
            List("product.unit_available"))
        )
      )
    catch case e: Exception => Left(e.getMessage)

  private def rabbitStream(
      c: Config,
      defaultExchange: String,
      defaultQueue: String,
      defaultRoutingKeys: List[String]
  ): RabbitStreamConfig =
    RabbitStreamConfig(
      c.getBoolean("enabled"),
      c.getString("host"),
      c.getInt("port"),
      c.getString("virtualHost"),
      c.getString("username"),
      c.getString("password"),
      exchangeKey(c, "exchange", defaultExchange),
      exchangeKey(c, "queue", defaultQueue),
      if c.hasPath("routingKeys") then c.getStringList("routingKeys").asScala.toList
      else defaultRoutingKeys
    )

  private def exchangeKey(c: Config, key: String, default: String): String =
    if c.hasPath(key) then c.getString(key) else default
