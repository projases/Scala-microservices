package edu.uoc.epcsd.productcatalog.config

import com.typesafe.config.{Config, ConfigFactory}

final case class ServerConfig(port: Int, host: String)
final case class RetryConfig(maxAttempts: Int, baseDelayMillis: Long)
final case class RabbitConfig(enabled: Boolean, host: String, port: Int, virtualHost: String, username: String, password: String)

final case class AppConfig(
    server: ServerConfig,
    retry: RetryConfig,
    rabbit: RabbitConfig
)

object AppConfig:
  def load: Either[String, AppConfig] =
    load(ConfigFactory.load())

  def load(config: Config): Either[String, AppConfig] =
    try
      val server = config.getConfig("server")
      val retry = config.getConfig("retry")
      val rabbit = config.getConfig("rabbit")
      Right(
        AppConfig(
          ServerConfig(server.getInt("port"), server.getString("host")),
          RetryConfig(retry.getInt("maxAttempts"), retry.getLong("baseDelayMillis")),
          RabbitConfig(
            rabbit.getBoolean("enabled"), rabbit.getString("host"), rabbit.getInt("port"),
            rabbit.getString("virtualHost"), rabbit.getString("username"), rabbit.getString("password")
          )
        )
      )
    catch case e: Exception => Left(e.getMessage)
