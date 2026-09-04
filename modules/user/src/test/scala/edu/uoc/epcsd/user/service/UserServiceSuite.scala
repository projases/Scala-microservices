package edu.uoc.epcsd.user.service

import java.time.LocalDate

import cats.data.EitherT
import cats.effect.IO
import io.circe.syntax.*
import munit.CatsEffectSuite

import edu.uoc.epcsd.user.domain.*
import edu.uoc.epcsd.user.domain.UserError.*
import edu.uoc.epcsd.user.http.Dtos.given

object Fakes:

  final case class FakeUserRepo(users: List[User] = Nil) extends UserRepository[IO]:
    var created: List[User] = Nil
    def findAllUsers: IO[List[User]] = IO.pure(users ++ created)
    def findUserById(id: Long): IO[Option[User]] = IO.pure((users ++ created).find(_.id == id))
    def findUserByEmail(email: String): IO[Option[User]] = IO.pure((users ++ created).find(_.email == email))
    def createUser(u: User): IO[User] =
      val next = u.copy(id = (users.size + created.size + 1).toLong)
      created = created :+ next
      IO.pure(next)

  final case class FakeAlertRepo(alerts: List[Alert] = Nil) extends AlertRepository[IO]:
    var created: List[Alert] = Nil
    private def all = alerts ++ created
    def findAllAlerts: IO[List[Alert]] = IO.pure(all)
    def findAlertById(id: Long): IO[Option[Alert]] = IO.pure(all.find(_.id == id))
    def findAlertsByProductAndDate(productId: Long, date: LocalDate): IO[List[Alert]] =
      IO.pure(all.filter(a => a.productId == productId && !a.from.isAfter(date) && !a.to.isBefore(date)))
    def findAlertsByUserAndInterval(userId: Long, from: LocalDate, to: LocalDate): IO[List[Alert]] =
      IO.pure(all.filter(a => a.userId == userId && ((!a.from.isAfter(from) && !a.to.isBefore(from)) || (!a.from.isAfter(to) && !a.to.isBefore(to)))))
    def createAlert(a: Alert): IO[Alert] =
      val next = a.copy(id = (all.size + 1).toLong)
      created = created :+ next
      IO.pure(next)

  final case class FakeProductSvc(exists: Boolean = true) extends ProductService[IO]:
    def existsById(productId: Long): Eff[IO, Boolean] =
      EitherT.right[UserError](IO.pure(exists))

class UserServiceSuite extends munit.CatsEffectSuite:

  private val jack = User(1L, "Jack", "jack@uoc.edu", "secret-pw", "123", UserType.Student)

  private def svc(userRepo: Fakes.FakeUserRepo,
                  alertRepo: Fakes.FakeAlertRepo = Fakes.FakeAlertRepo(),
                  productSvc: Fakes.FakeProductSvc = Fakes.FakeProductSvc()) =
    new UserService[IO](userRepo, alertRepo, productSvc)

  test("getAllUsers never exposes the password field on the wire") {
    val s = svc(userRepo = Fakes.FakeUserRepo(List(jack)))
    for
      users <- s.findAllUsers
      json   = users.asJson.noSpaces
      _      = assertEquals(users.map(_.email), List("jack@uoc.edu"))
      _      = assertEquals(json.contains("password"), false)
      _      = assertEquals(json.contains("full_name"), false, "json should be camelCase, not snake_case")
      _      = assertEquals(json.contains("\"fullName\""), true)
    yield ()
  }

  test("getUserByEmail returns a passwordless projection") {
    val s = svc(userRepo = Fakes.FakeUserRepo(List(jack)))
    s.getUserByEmail("jack@uoc.edu").map {
      case Some(u) =>
        assertEquals(u.fullName, "Jack")
        assertEquals(u.`type`, UserType.Student)
      case None => fail("expected user")
    }
  }

  test("createAlert succeeds when user and product exist") {
    val alertRepo = Fakes.FakeAlertRepo()
    val s = svc(
      userRepo = Fakes.FakeUserRepo(List(jack)),
      alertRepo = alertRepo,
      productSvc = Fakes.FakeProductSvc(exists = true)
    )
    s.createAlert(CreateAlertRequest(10L, 1L, LocalDate.of(2026,1,1), LocalDate.of(2026,1,31))).value.map {
      case Right(id) =>
        assertEquals(id, 1L)
        assertEquals(alertRepo.created.head.productId, 10L)
      case Left(e) => fail(s"expected success, got $e")
    }
  }

  test("createAlert fails when the product does not exist") {
    val s = svc(
      userRepo = Fakes.FakeUserRepo(List(jack)),
      productSvc = Fakes.FakeProductSvc(exists = false)
    )
    s.createAlert(CreateAlertRequest(99L, 1L, LocalDate.of(2026,1,1), LocalDate.of(2026,1,31))).value.map {
      case Left(ProductDoesNotExist(pid)) => assertEquals(pid, 99L)
      case other => fail(s"expected ProductDoesNotExist, got $other")
    }
  }

  test("createAlert fails when the user does not exist") {
    val s = svc(userRepo = Fakes.FakeUserRepo(Nil))
    s.createAlert(CreateAlertRequest(10L, 1L, LocalDate.of(2026,1,1), LocalDate.of(2026,1,31))).value.map {
      case Left(UserDoesNotExist(uid)) => assertEquals(uid, 1L)
      case other => fail(s"expected UserDoesNotExist, got $other")
    }
  }
