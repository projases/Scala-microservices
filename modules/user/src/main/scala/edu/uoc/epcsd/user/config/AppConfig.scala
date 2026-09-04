package edu.uoc.epcsd.user.config

import com.typesafe.config.{Config, ConfigFactory}

final case class DatabaseConfig(driver: String, url: String, user: String, password: String, migrate: Boolean)
final case class ServerConfig(port: Int, host: String)
final case class ProductServiceConfig(baseUrl: String)

final case class AppConfig(
    database: DatabaseConfig,
    server: ServerConfig,
    productService: ProductServiceConfig
)

object AppConfig:
  def load: Either[String, AppConfig] = load(ConfigFactory.load())

  def load(config: Config): Either[String, AppConfig] =
    try
      val db = config.getConfig("database")
      val server = config.getConfig("server")
      val product = config.getConfig("productService")
      Right(
        AppConfig(
          DatabaseConfig(
            db.getString("driver"), db.getString("url"),
            db.getString("user"), db.getString("password"), db.getBoolean("migrate")
          ),
          ServerConfig(server.getInt("port"), server.getString("host")),
          ProductServiceConfig(product.getString("baseUrl"))
        )
      )
    catch case e: Exception => Left(e.getMessage)
