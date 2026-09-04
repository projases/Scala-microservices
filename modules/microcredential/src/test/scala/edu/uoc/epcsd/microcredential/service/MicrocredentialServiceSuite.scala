package edu.uoc.epcsd.microcredential.service

import java.time.Instant

import cats.data.EitherT
import cats.effect.{Clock, IO}
import munit.CatsEffectSuite

import edu.uoc.epcsd.microcredential.domain.*
import edu.uoc.epcsd.microcredential.domain.MicrocredentialError.*

object Fakes:

  final case class FakeMicrocredentialRepo(
      byId: Map[Long, Microcredential] = Map.empty
  ):
    var stored: Map[Long, Microcredential] = byId
    var counter: Long = 0L

    def repo: MicrocredentialRepository[IO] = new MicrocredentialRepository[IO]:
      def getById(id: Long): IO[Option[Microcredential]] =
        IO.pure(stored.get(id))
      def getByEnrollment(enrollmentId: Long): IO[Option[Microcredential]] =
        IO.pure(stored.values.find(_.enrollment == enrollmentId))
      def create(m: Microcredential): IO[Microcredential] =
        IO {
          val saved = m.copy(id = counter)
          counter += 1
          stored = stored + (saved.id -> saved)
          saved
        }
      def update(m: Microcredential): IO[Unit] =
        IO { stored = stored + (m.id -> m) }
      def getPendingRequests: IO[List[Microcredential]] =
        IO.pure(stored.values.filter(_.status == MicrocredentialStatus.Requested).toList)

  final case class FakeCourseService(
      enrollments: List[EnrollmentResponse] = Nil,
      singleEnrollment: Option[EnrollmentResponse] = None
  ) extends CourseService[IO]:
    def getCourseEnrollments(courseId: Long): Eff[IO, List[EnrollmentResponse]] =
      if enrollments.isEmpty then EitherT.leftT(NoEnrollments(courseId))
      else EitherT.rightT(enrollments)
    def getEnrollment(enrollmentId: Long): Eff[IO, EnrollmentResponse] =
      singleEnrollment match
        case Some(e) => EitherT.rightT(e)
        case None    => EitherT.leftT(CourseServiceUnavailable(s"enrollment $enrollmentId not found"))

  final case class FakeEventPublisher():
    var published: List[(String, MicrocredentialEventMessage)] = Nil
    def publisher: MicrocredentialEventPublisher[IO] = new MicrocredentialEventPublisher[IO]:
      def publish(cmd: String, msg: MicrocredentialEventMessage): IO[Unit] =
        IO { published = published :+ (cmd -> msg) }

  def fixedClock(instant: Instant): Clock[IO] = new Clock[IO]:
    override def realTime: IO[scala.concurrent.duration.FiniteDuration] =
      IO.pure(scala.concurrent.duration.FiniteDuration(instant.getEpochSecond, java.util.concurrent.TimeUnit.SECONDS))
    override def realTimeInstant: IO[Instant] = IO.pure(instant)
    override def monotonic: IO[scala.concurrent.duration.FiniteDuration] =
      IO.pure(scala.concurrent.duration.FiniteDuration(instant.getEpochSecond, java.util.concurrent.TimeUnit.SECONDS))
    override def applicative: cats.Applicative[IO] = summon[cats.Applicative[IO]]

class MicrocredentialServiceSuite extends munit.CatsEffectSuite:

  private val enrollment1 = EnrollmentResponse(10L, "student@uoc.edu", 1L)
  private val enrollment2 = EnrollmentResponse(11L, "other@uoc.edu", 1L)
  private val now         = Instant.parse("2026-03-01T12:00:00Z")

  private def service(
      repo: Fakes.FakeMicrocredentialRepo,
      courseSvc: Fakes.FakeCourseService = Fakes.FakeCourseService(),
      publisher: Fakes.FakeEventPublisher = Fakes.FakeEventPublisher()
  ): MicrocredentialService[IO] =
    new MicrocredentialService[IO](
      repo.repo, courseSvc, publisher.publisher, Fakes.fixedClock(now)
    )

  test("getMicrocredentialById returns Some when it exists") {
    val mc = Microcredential(1L, now, None, MicrocredentialStatus.Requested, "", 10L)
    val repo = Fakes.FakeMicrocredentialRepo(byId = Map(1L -> mc))
    service(repo).getMicrocredentialById(1L).map {
      case Some(m) => assertEquals(m.id, 1L)
      case None    => fail("expected microcredential")
    }
  }

  test("getMicrocredentialById returns None when not found") {
    service(Fakes.FakeMicrocredentialRepo()).getMicrocredentialById(99L).map {
      case None => ()
      case _    => fail("expected None")
    }
  }

  test("approvePendingMicrocredential transitions REQUESTED to GRANTED and publishes") {
    val mc = Microcredential(1L, now, None, MicrocredentialStatus.Requested, "", 10L)
    val repo = Fakes.FakeMicrocredentialRepo(byId = Map(1L -> mc))
    val pub = Fakes.FakeEventPublisher()
    val courseSvc = Fakes.FakeCourseService(singleEnrollment = Some(enrollment1))
    val svc = service(repo, courseSvc, pub)

    svc.approvePendingMicrocredential(1L).value.flatMap {
      case Right(()) =>
        IO {
          assertEquals(repo.stored(1L).status, MicrocredentialStatus.Granted)
          assertEquals(repo.stored(1L).assignmentDate, Some(now))
          assertEquals(pub.published.length, 1)
          assertEquals(pub.published.head._1, MicrocredentialEventPublisher.Approved)
          assertEquals(pub.published.head._2.userEmail, "student@uoc.edu")
          assertEquals(pub.published.head._2.courseId, 1L)
        }
      case Left(e) => IO(fail(s"expected success, got $e"))
    }
  }

  test("approvePendingMicrocredential fails with NotFound") {
    service(Fakes.FakeMicrocredentialRepo()).approvePendingMicrocredential(99L).value.map {
      case Left(NotFound(id)) => assertEquals(id, 99L)
      case other              => fail(s"expected NotFound, got $other")
    }
  }

  test("approvePendingMicrocredential fails with InvalidState when not REQUESTED") {
    val mc = Microcredential(1L, now, Some(now), MicrocredentialStatus.Granted, "", 10L)
    val repo = Fakes.FakeMicrocredentialRepo(byId = Map(1L -> mc))
    service(repo).approvePendingMicrocredential(1L).value.map {
      case Left(InvalidState(expected, actual)) =>
        assertEquals(expected, "REQUESTED")
        assertEquals(actual, MicrocredentialStatus.Granted.toString)
      case other => fail(s"expected InvalidState, got $other")
    }
  }

  test("rejectPendingMicrocredential transitions REQUESTED to REJECTED and publishes") {
    val mc = Microcredential(1L, now, None, MicrocredentialStatus.Requested, "", 10L)
    val repo = Fakes.FakeMicrocredentialRepo(byId = Map(1L -> mc))
    val pub = Fakes.FakeEventPublisher()
    val courseSvc = Fakes.FakeCourseService(singleEnrollment = Some(enrollment1))
    val svc = service(repo, courseSvc, pub)

    svc.rejectPendingMicrocredential(1L).value.flatMap {
      case Right(()) =>
        IO {
          assertEquals(repo.stored(1L).status, MicrocredentialStatus.Rejected)
          assertEquals(pub.published.head._1, MicrocredentialEventPublisher.Rejected)
        }
      case Left(e) => IO(fail(s"expected success, got $e"))
    }
  }

  test("getPendingMicrocredentialRequests returns only REQUESTED") {
    val req  = Microcredential(1L, now, None, MicrocredentialStatus.Requested, "", 10L)
    val done = Microcredential(2L, now, Some(now), MicrocredentialStatus.Granted, "", 11L)
    val repo = Fakes.FakeMicrocredentialRepo(byId = Map(1L -> req, 2L -> done))
    service(repo).getPendingMicrocredentialRequests.map { list =>
      assertEquals(list.map(_.id), List(1L))
    }
  }

  test("requestCourseMicrocredentials creates a microcredential per enrollment and publishes Pending") {
    val repo = Fakes.FakeMicrocredentialRepo()
    val pub = Fakes.FakeEventPublisher()
    val courseSvc = Fakes.FakeCourseService(enrollments = List(enrollment1, enrollment2))
    val svc = service(repo, courseSvc, pub)

    svc.requestCourseMicrocredentials(1L).value.flatMap {
      case Right(()) =>
        IO {
          assertEquals(repo.stored.size, 2)
          assertEquals(repo.stored.values.count(_.status == MicrocredentialStatus.Requested), 2)
          assertEquals(pub.published.length, 2)
          assertEquals(pub.published.map(_._1).distinct, List(MicrocredentialEventPublisher.Pending))
        }
      case Left(e) => IO(fail(s"expected success, got $e"))
    }
  }

  test("requestCourseMicrocredentials is idempotent per enrollment") {
    val existing = Microcredential(1L, now, None, MicrocredentialStatus.Requested, "", 10L)
    val repo = Fakes.FakeMicrocredentialRepo(byId = Map(1L -> existing))
    val pub = Fakes.FakeEventPublisher()
    val courseSvc = Fakes.FakeCourseService(enrollments = List(enrollment1, enrollment2))
    val svc = service(repo, courseSvc, pub)

    svc.requestCourseMicrocredentials(1L).value.flatMap {
      case Right(()) =>
        IO {
          // enrollment1 already has a microcredential → only enrollment2 is created
          assertEquals(repo.stored.size, 2)
          assertEquals(pub.published.length, 1)
        }
      case Left(e) => IO(fail(s"expected success, got $e"))
    }
  }

  test("requestCourseMicrocredentials fails with NoEnrollments when no enrollments") {
    val repo = Fakes.FakeMicrocredentialRepo()
    val courseSvc = Fakes.FakeCourseService(enrollments = Nil)
    val svc = service(repo, courseSvc)

    svc.requestCourseMicrocredentials(1L).value.map {
      case Left(NoEnrollments(cid)) => assertEquals(cid, 1L)
      case other                    => fail(s"expected NoEnrollments, got $other")
    }
  }