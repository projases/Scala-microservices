package edu.uoc.epcsd.microcredential.config

import com.typesafe.config.{Config, ConfigFactory}

final case class DatabaseConfig(driver: String, url: String, user: String, password: String, migrate: Boolean, poolSize: Int = 8)
final case class ServerConfig(port: Int, host: String)
final case class CourseServiceConfig(baseUrl: String)
final case class RetryConfig(maxAttempts: Int, baseDelayMillis: Long)
final case class RabbitConfig(
    enabled: Boolean,
    host: String,
    port: Int,
    virtualHost: String,
    username: String,
    password: String,
    exchange: String,
    routingKey: String
)

final case class AppConfig(
    database: DatabaseConfig,
    server: ServerConfig,
    courseService: CourseServiceConfig,
    retry: RetryConfig,
    rabbit: RabbitConfig
)

object AppConfig:
  def load: Either[String, AppConfig] = load(ConfigFactory.load())

  def load(config: Config): Either[String, AppConfig] =
    try
      val db = config.getConfig("database")
      val server = config.getConfig("server")
      val course = config.getConfig("courseService")
      val retry = config.getConfig("retry")
      val rabbit = config.getConfig("rabbit")
      Right(
        AppConfig(
          DatabaseConfig(
            db.getString("driver"), db.getString("url"),
            db.getString("user"), db.getString("password"), db.getBoolean("migrate"),
            if db.hasPath("poolSize") then db.getInt("poolSize") else 8
          ),
          ServerConfig(server.getInt("port"), server.getString("host")),
          CourseServiceConfig(course.getString("baseUrl")),
          RetryConfig(retry.getInt("maxAttempts"), retry.getLong("baseDelayMillis")),
          RabbitConfig(
            rabbit.getBoolean("enabled"), rabbit.getString("host"), rabbit.getInt("port"),
            rabbit.getString("virtualHost"), rabbit.getString("username"), rabbit.getString("password"),
            rabbit.getString("exchange"), rabbit.getString("routingKey")
          )
        )
      )
    catch case e: Exception => Left(e.getMessage)
