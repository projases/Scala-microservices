package edu.uoc.epcsd.user

import cats.effect.{IO, IOApp, Resource}
import cats.syntax.all.*
import com.comcast.ip4s.{Host, Port}
import doobie.hikari.HikariTransactor
import doobie.util.ExecutionContexts
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Router
import org.flywaydb.core.Flyway

import config.AppConfig
import infrastructure.product.ProductClient
import infrastructure.repository.{DoobieAlertRepository, DoobieUserRepository}
import service.UserService
import http.{ApiDocs, Routes}

object Main extends IOApp.Simple:

  def run: IO[Unit] =
    for
      cfg <- AppConfig.load.fold(e => IO.raiseError[AppConfig](new RuntimeException(e)), IO.pure)
      _   <- IO.whenA(cfg.database.migrate)(migrate(cfg))
      _   <- server(cfg).useForever
    yield ()

  private def migrate(cfg: AppConfig): IO[Unit] =
    IO {
      Flyway.configure()
        .dataSource(cfg.database.url, cfg.database.user, cfg.database.password)
        .load()
        .migrate()
    }.void

  private def server(cfg: AppConfig): Resource[IO, Unit] =
    for
      xa       <- transactor(cfg)
      client   <- EmberClientBuilder.default[IO].build
      userRepo  = new DoobieUserRepository[IO](xa)
      alertRepo = new DoobieAlertRepository[IO](xa)
      productSvc = new ProductClient[IO](client, cfg.productService.baseUrl)
      svc       = new UserService[IO](userRepo, alertRepo, productSvc)
      api       = Routes[IO](svc) <+> ApiDocs.routes[IO]
      app       = Router("/" -> api).orNotFound
      _ <- EmberServerBuilder
             .default[IO]
             .withHost(Host.fromString(cfg.server.host).get)
             .withPort(Port.fromInt(cfg.server.port).get)
             .withHttpApp(app)
             .build
    yield ()

  private def transactor(cfg: AppConfig): Resource[IO, HikariTransactor[IO]] =
    for
      ce <- ExecutionContexts.fixedThreadPool[IO](8)
      xa <- HikariTransactor.newHikariTransactor[IO](
              cfg.database.driver, cfg.database.url, cfg.database.user, cfg.database.password, ce
            )
    yield xa
