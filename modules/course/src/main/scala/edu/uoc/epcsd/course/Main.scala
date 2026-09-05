package edu.uoc.epcsd.course

import cats.data.NonEmptyList
import cats.effect.{IO, IOApp, Resource}
import cats.effect.kernel.Clock
import cats.syntax.all.*
import com.comcast.ip4s.{Host, Port}
import dev.profunktor.fs2rabbit.config.{Fs2RabbitConfig, Fs2RabbitNodeConfig}
import dev.profunktor.fs2rabbit.interpreter.RabbitClient
import dev.profunktor.fs2rabbit.model.{ExchangeName, QueueName, RoutingKey}
import doobie.hikari.HikariTransactor
import doobie.util.ExecutionContexts
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Router
import org.flywaydb.core.Flyway
import scala.concurrent.duration.*

import config.{AppConfig, RabbitConfig}
import domain.CourseEventPublisher
import infrastructure.repository.{DoobieCourseRepository, DoobieEnrollmentRepository}
import infrastructure.user.UserClient
import infrastructure.microcredential.MicrocredentialClient
import infrastructure.events.{CourseClosedConsumer, NoopCourseEventPublisher, RabbitCourseEventPublisher}
import service.CourseService
import http.{ApiDocs, Routes}

object Main extends IOApp.Simple:

  def run: IO[Unit] =
    for
      cfg <- AppConfig.load.fold(e => IO.raiseError[AppConfig](new RuntimeException(e)), IO.pure)
      _   <- IO.whenA(cfg.database.migrate)(migrate(cfg))
      _   <- server(cfg).useForever
    yield ()

// Flyway is a database migration tool that helps manage and version control database schema changes. It allows developers to define and apply database migrations in a structured manner, ensuring that the database schema is consistent across different environments. Flyway supports various databases and provides features like versioning, rollback, and repeatable migrations. In this code, Flyway is used to automatically apply any pending migrations to the database before starting the application server.migrate runs the Flyway database migration using the provided configuration. It configures Flyway with the database connection details and executes the migration. The result is an IO[Unit] that represents the completion of the migration process.

  private def migrate(cfg: AppConfig): IO[Unit] =
    IO {
      Flyway.configure()
        .dataSource(cfg.database.url, cfg.database.user, cfg.database.password)
        .load()
        .migrate()
    }.void

  private def server(cfg: AppConfig): Resource[IO, Unit] =
    for
      // Transactor is a Resource that manages the lifecycle of the database connection pool. 
      xa <- transactor(cfg)
// EmberClientBuilder is a Resource that manages the lifecycle of the HTTP client.
      client <- EmberClientBuilder.default[IO].build
// The following lines create the repositories, services, and routes for the application.
      courseRepo   = new DoobieCourseRepository[IO](xa)
      enrollRepo   = new DoobieEnrollmentRepository[IO](xa)
      userSvc      = new UserClient[IO](client, cfg.userService.baseUrl)
      mcSvc        = new MicrocredentialClient[IO](client, cfg.microcredentialService.baseUrl, cfg.retry)
      events       <- courseEventPublisher(cfg)
      svc          = new CourseService[IO](courseRepo, enrollRepo, userSvc, mcSvc, Clock[IO], events)
// The following line creates the HTTP application by routing requests to the appropriate service methods.
      api          = Routes[IO](svc) <+> ApiDocs.routes[IO]
      app          = Router("/" -> api).orNotFound
      _ <- EmberServerBuilder
             .default[IO]
             .withHost(Host.fromString(cfg.server.host).get)
             .withPort(Port.fromInt(cfg.server.port).get)
             .withHttpApp(app)
             .build
    yield ()

  /** Resolves the RabbitMQ-backed publisher + in-process consumer, or a no-op when disabled.
    *  Both live only while the server runs; their background fibers are cancelled on shutdown.
    */
  private def courseEventPublisher(cfg: AppConfig): Resource[IO, CourseEventPublisher[IO]] =
    val r = cfg.rabbit
    if r.enabled then
      RabbitClient.default[IO](toFs2RabbitConfig(r)).resource.flatMap { client =>
          val publisher = RabbitCourseEventPublisher[IO](
            client, ExchangeName(r.exchange), QueueName(r.queue), RoutingKey(r.routingKey), cfg.retry
          )
          val consumer =
            CourseClosedConsumer[IO](client, QueueName(r.queue), CourseClosedConsumer.loggingHandler[IO])
          (publisher, consumer).mapN((p, _) => p)
        }
    else Resource.pure(NoopCourseEventPublisher[IO])

  private def toFs2RabbitConfig(r: RabbitConfig): Fs2RabbitConfig =
    Fs2RabbitConfig(
      virtualHost = r.virtualHost,
      nodes = NonEmptyList.one(Fs2RabbitNodeConfig(host = r.host, port = r.port)),
      username = Some(r.username),
      password = Some(r.password),
      ssl = false,
      connectionTimeout = 3.seconds,
      requeueOnNack = false,
      requeueOnReject = false,
      internalQueueSize = Some(500),
      requestedHeartbeat = 30.seconds,
      automaticRecovery = true,
      automaticTopologyRecovery = true,
      clientProvidedConnectionName = Some("course-service")
    )

// transactor is a Resource that manages the lifecycle of the database connection pool. It uses HikariCP for connection pooling and Doobie for database access. The transactor is created with a fixed thread pool of 8 threads, which is used for executing database operations.
// what is connection pooling? Connection pooling is a technique used to manage database connections efficiently. Instead of creating a new connection for each database operation, a pool of connections is maintained and reused. This reduces the overhead of establishing and closing connections, improves performance, and allows for better resource management. HikariCP is a popular high-performance JDBC connection pool that provides efficient connection pooling for applications. 
  private def transactor(cfg: AppConfig): Resource[IO, HikariTransactor[IO]] =
    for
      // why ce? ce stands for "connection execution context". It is a fixed thread pool that is used to execute database operations in a separate thread pool. This allows for better performance and prevents blocking the main application threads. The fixed thread pool is created with 8 threads, which is a reasonable number for handling concurrent database operations without overwhelming the system.
      ce <- ExecutionContexts.fixedThreadPool[IO](cfg.database.poolSize)
// why xa? xa is a common variable name used to represent a transactor in Doobie. It stands for "transactor" and is used to execute database operations. The transactor is responsible for managing the database connection and executing queries in a safe and efficient manner. In this case, xa is created using HikariTransactor, which provides connection pooling and integrates with Doobie for database access.
      xa <- HikariTransactor.newHikariTransactor[IO](
              cfg.database.driver,
              cfg.database.url,
              cfg.database.user,
              cfg.database.password,
              ce
            )
    yield xa
