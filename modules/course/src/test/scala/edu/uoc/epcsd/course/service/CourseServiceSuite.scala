package edu.uoc.epcsd.course.service

import java.time.LocalDate

import cats.effect.IO
import cats.effect.kernel.Clock
import cats.data.EitherT
import munit.CatsEffectSuite

import edu.uoc.epcsd.course.domain.*
import edu.uoc.epcsd.course.domain.CourseError.*

/** In-memory interpreters so the pure service logic is tested with zero infra.
  *
  *  `FakeStore` holds both tables so the transactional workflow methods
  *  (`persistGradeReportClosure` / `persistCourseClosure`) can be exercised atomically across
  *  the course and enrollment aggregates, exactly like the doobie `transact(xa)` boundary.
  */
object Fakes:

  final class FakeStore:
    var courses: Vector[Course]     = Vector.empty
    var enrollments: Vector[Enrollment] = Vector.empty
    var failNextClosure: Boolean    = false

  final class FakeCourseRepo(val store: FakeStore) extends CourseRepository[IO]:
    def getCourseById(id: Long): IO[Option[Course]] = IO.pure(store.courses.find(_.id.contains(id)))
    def findCourses: IO[List[Course]]               = IO.pure(store.courses.toList)
    def createCourse(c: Course): IO[Course]         =
      val next = c.copy(id = Some((store.courses.size + 1).toLong))
      store.courses = store.courses :+ next
      IO.pure(next)
    def updateCourse(c: Course): IO[Unit]           =
      store.courses = store.courses.map(x => if x.id == c.id then c else x)
      IO.unit
    def persistGradeReportClosure(course: Course, gradedEnrollments: List[Enrollment]): IO[Unit] =
      applyClosure(course, CourseStatus.PendingClosure, gradedEnrollments, EnrollmentStatus.Graded)
    def persistCourseClosure(course: Course, closedEnrollments: List[Enrollment]): IO[Unit] =
      applyClosure(course, CourseStatus.Closed, closedEnrollments, EnrollmentStatus.Closed)

    /** Apply the workflow atomically: build the full next state first, then commit it. A failure
      *  leaves the store untouched — the same guarantee the single `transact(xa)` provides.
      */
    private def applyClosure(
        course: Course,
        courseStatus: CourseStatus,
        enrollments: List[Enrollment],
        enrollmentStatus: EnrollmentStatus
    ): IO[Unit] =
      if store.failNextClosure then
        store.failNextClosure = false
        IO.raiseError(new RuntimeException("simulated mid-workflow DB failure"))
      else
        val updatedCourse   =
          store.courses.map(x => if x.id == course.id then course.copy(status = courseStatus) else x)
        val gradedIds       = enrollments.flatMap(_.id).toSet
        val updatedEnrolls  =
          store.enrollments.map(e => if e.id.exists(gradedIds.contains) then e.copy(status = enrollmentStatus) else e)
        store.courses = updatedCourse
        store.enrollments = updatedEnrolls
        IO.unit

  final class FakeEnrollmentRepo(val store: FakeStore) extends EnrollmentRepository[IO]:
    def findEnrollmentByCourse(courseId: Long): IO[List[Enrollment]] =
      IO.pure(store.enrollments.filter(_.courseId == courseId).toList)
    def findEnrollmentByStudent(email: String): IO[Option[Enrollment]] =
      IO.pure(store.enrollments.find(_.student == email))
    def getEnrollmentById(id: Long): IO[Option[Enrollment]] = IO.pure(store.enrollments.find(_.id.contains(id)))
    def createEnrollment(e: Enrollment): IO[Enrollment]     =
      val next = e.copy(id = Some((store.enrollments.size + 1).toLong))
      store.enrollments = store.enrollments :+ next
      IO.pure(next)
    def updateEnrollment(e: Enrollment): IO[Unit]           =
      store.enrollments = store.enrollments.map(x => if x.id == e.id then e else x)
      IO.unit

  final case class FakeUserService(users: List[User] = Nil) extends UserService[IO]:
    def getUserByEmail(email: String): IO[Option[User]] =
      IO.pure(users.find(_.email == email))
    def isInstructor(email: String): IO[Boolean] =
      IO.pure(users.exists(u => u.email == email && u.status == UserType.Instructor))

  final case class FakeMicrocredentialSvc(fail: Boolean = false) extends MicrocredentialService[IO]:
    def requestMicrocredentials(courseId: Long): Eff[IO, Unit] =
      if fail then EitherT.leftT(MicrocredentialServiceUnavailable("down"))
      else EitherT.rightT(())

  final case class FakeCourseEventPublisher(var closed: List[CourseClosed] = Nil)
      extends CourseEventPublisher[IO]:
    def publishClosed(course: Course): IO[Unit] =
      val courseId = course.id.getOrElse(throw new IllegalStateException("expected persisted course"))
      IO { closed = closed :+ CourseClosed(courseId, course.title) }

/** Coursier-test suite for the pure service logic: success + short-circuit paths. */
class CourseServiceSuite extends munit.CatsEffectSuite:

  private val instructor = User(1L, "Prof", "instr@uoc.edu", "123", UserType.Instructor)
  private val student    = User(2L, "Alum", "student@uoc.edu", "456", UserType.Student)

  private val baseCourse = Course(
    id = None, instructor = "instr@uoc.edu",
    title = "FP", description = "desc",
    enrollmentStartDate = LocalDate.of(2026, 1, 1),
    enrollmentEndDate   = LocalDate.of(2026, 6, 30),
    mode = "Online", price = 100, objectives = "o", methology = "m",
    duration = 40, language = "en", location = "web", status = CourseStatus.Draft
  )

  private def newStore(
      courses: List[Course] = Nil,
      enrollments: List[Enrollment] = Nil
  ): Fakes.FakeStore =
    val s = new Fakes.FakeStore
    s.courses = courses.toVector
    s.enrollments = enrollments.toVector
    s

  private def repos(store: Fakes.FakeStore): (Fakes.FakeCourseRepo, Fakes.FakeEnrollmentRepo) =
    (new Fakes.FakeCourseRepo(store), new Fakes.FakeEnrollmentRepo(store))

  private def service(
      courseRepo: Fakes.FakeCourseRepo,
      enrollmentRepo: Fakes.FakeEnrollmentRepo,
      userSvc: Fakes.FakeUserService,
      mcSvc: Fakes.FakeMicrocredentialSvc = Fakes.FakeMicrocredentialSvc(),
      events: Fakes.FakeCourseEventPublisher = Fakes.FakeCourseEventPublisher()
  ): CourseService[IO] =
    new CourseService[IO](courseRepo, enrollmentRepo, userSvc, mcSvc, Clock[IO], events)

  test("createCourse with valid instructor and unique title returns Right(id)") {
    val store = newStore()
    val (repo, enrollRepo) = repos(store)
    val svc = service(repo, enrollRepo, Fakes.FakeUserService(List(instructor)))
    val req = CreateCourse(
      "instr@uoc.edu", "FP", "desc",
      LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30),
      "Online", 100, "o", "m", 40, "en", "web"
    )
    svc.createCourse(req).value.map {
      case Right(id) =>
        assertEquals(id, 1L)
        assertEquals(store.courses.headOption.map(c => (c.id, c.status)), Some((Some(1L), CourseStatus.Draft)))
      case Left(_) => fail("expected success")
    }
  }

  test("createCourse rejects a non-instructor email") {
    val store = newStore()
    val (repo, enrollRepo) = repos(store)
    val svc = service(repo, enrollRepo, Fakes.FakeUserService(List(student)))
    val req = CreateCourse("student@uoc.edu", "X", "d", LocalDate.of(2026,1,1), LocalDate.of(2026,6,30), "Online", 1, "o","m",1,"en","web")
    svc.createCourse(req).value.map {
      case Left(InstructorNotFound(email)) => assertEquals(email, "student@uoc.edu")
      case _ => fail("expected InstructorNotFound")
    }
  }

  test("createCourse rejects a duplicate title") {
    val existing = baseCourse.copy(id = Some(1L), title = "FP", status = CourseStatus.Active)
    val store = newStore(courses = List(existing))
    val (repo, enrollRepo) = repos(store)
    val svc = service(repo, enrollRepo, Fakes.FakeUserService(List(instructor)))
    val req = CreateCourse("instr@uoc.edu", "FP", "d", LocalDate.of(2026,1,1), LocalDate.of(2026,6,30), "Online", 1, "o","m",1,"en","web")
    svc.createCourse(req).value.map {
      case Left(DuplicateCourseTitle(t)) => assertEquals(t, "FP")
      case _ => fail("expected DuplicateCourseTitle")
    }
  }

  test("openEnrollment only from DRAFT; short-circuits from ACTIVE") {
    val active = baseCourse.copy(id = Some(1L), status = CourseStatus.Active)
    val store = newStore(courses = List(active))
    val (repo, enrollRepo) = repos(store)
    val svc = service(repo, enrollRepo, Fakes.FakeUserService(Nil))
    svc.openEnrollment(1L, LocalDate.of(2026,1,1), LocalDate.of(2026,6,30)).value.map {
      case Left(InvalidState(expected, actual)) =>
        assertEquals(expected, "enrollment must be DRAFT to open")
        assertEquals(actual, CourseStatus.Active.toString)
      case _ => fail("expected InvalidState")
    }
  }

  test("full lifecycle: open -> enroll -> close -> grade -> close course publishes CourseClosed") {
    val draft = baseCourse.copy(id = Some(1L))
    val store = newStore(courses = List(draft))
    val (courseRepo, enrollRepo) = repos(store)
    val events = Fakes.FakeCourseEventPublisher()
    val svc = service(courseRepo, enrollRepo, Fakes.FakeUserService(List(instructor, student)), events = events)

    val lifecycle = for
      _ <- svc.openEnrollment(1L, LocalDate.of(2026,1,1), LocalDate.of(2026,6,30))
      _ <- svc.enrollInCourse(1L, "student@uoc.edu")
      _ <- svc.closeEnrollment(1L)
      _ <- svc.closeGradeReports(1L)
      _ <- svc.closeCourse(1L)
    yield ()

    lifecycle.value.flatMap {
      case Right(_) =>
        for
          afterClose <- courseRepo.getCourseById(1L)
          graded     <- enrollRepo.findEnrollmentByCourse(1L)
        yield
          assertEquals(afterClose.get.status, CourseStatus.Closed)
          assertEquals(graded.forall(_.status == EnrollmentStatus.Closed), true)
          // the close emits exactly one async CourseClosed event, after the DB commit
          assertEquals(events.closed, List(CourseClosed(1L, "FP")))
      case Left(err) => fail(s"expected success, got $err")
    }
  }

  test("closeCourse fails cleanly when the microcredential service is down") {
    val active = baseCourse.copy(id = Some(1L), status = CourseStatus.Active)
    val store = newStore(courses = List(active))
    val (repo, enrollRepo) = repos(store)
    val events = Fakes.FakeCourseEventPublisher()
    val svc = service(
      repo, enrollRepo, Fakes.FakeUserService(Nil),
      Fakes.FakeMicrocredentialSvc(fail = true), events
    )
    svc.closeCourse(1L).value.map {
      case Left(MicrocredentialServiceUnavailable(_)) =>
        // no event is emitted when the close itself fails
        assertEquals(events.closed, Nil)
      case other => fail(s"expected MicrocredentialServiceUnavailable, got $other")
    }
  }

  test("closeCourse rejects when not all enrollments are graded") {
    val course = baseCourse.copy(id = Some(1L), status = CourseStatus.Active)
    val store = newStore(
      courses = List(course),
      enrollments = List(
        Enrollment(Some(10L), "student@uoc.edu", LocalDate.of(2026, 5, 1), 0L, EnrollmentStatus.Active, 1L)
      )
    )
    val (repo, enrollRepo) = repos(store)
    val svc = service(repo, enrollRepo, Fakes.FakeUserService(List(instructor, student)))
    svc.closeCourse(1L).value.map {
      case Left(EnrollmentsNotGraded(courseId)) => assertEquals(courseId, 1L)
      case other => fail(s"expected EnrollmentsNotGraded, got $other")
    }
  }

  test("getEnrolledStudents keeps enrollment order and drops unknown users") {
    val draft = baseCourse.copy(id = Some(1L))
    val store = newStore(
      courses = List(draft),
      enrollments = List(
        Enrollment(Some(1L), "student@uoc.edu", LocalDate.of(2026, 3, 1), 0L, EnrollmentStatus.Active, 1L),
        Enrollment(Some(2L), "ghost@uoc.edu",    LocalDate.of(2026, 3, 2), 0L, EnrollmentStatus.Active, 1L),
        Enrollment(Some(3L), "instr@uoc.edu",    LocalDate.of(2026, 3, 3), 0L, EnrollmentStatus.Active, 1L)
      )
    )
    val (courseRepo, enrollRepo) = repos(store)
    val svc = service(courseRepo, enrollRepo, Fakes.FakeUserService(List(student, instructor)))
    svc.getEnrolledStudents(1L).map { users =>
      assertEquals(users.map(_.email), List("student@uoc.edu", "instr@uoc.edu"))
    }
  }

  test("enrollInCourse rejects unknown user") {
    val open = baseCourse.copy(id = Some(1L), status = CourseStatus.EnrollmentOpen)
    val store = newStore(courses = List(open))
    val (repo, enrollRepo) = repos(store)
    val svc = service(repo, enrollRepo, Fakes.FakeUserService(Nil))
    svc.enrollInCourse(1L, "ghost@uoc.edu").value.map {
      case Left(UserNotFound(email, _)) => assertEquals(email, "ghost@uoc.edu")
      case _ => fail("expected UserNotFound")
    }
  }

  test("closeCourse rolls back atomically when the closure commit fails") {
    val course = baseCourse.copy(id = Some(1L), status = CourseStatus.Active)
    val store = newStore(
      courses = List(course),
      enrollments = List(
        Enrollment(Some(10L), "student@uoc.edu", LocalDate.of(2026, 5, 1), 0L, EnrollmentStatus.Graded, 1L)
      )
    )
    val (courseRepo, enrollRepo) = repos(store)
    val events = Fakes.FakeCourseEventPublisher()
    val svc = service(courseRepo, enrollRepo, Fakes.FakeUserService(List(instructor, student)), events = events)

    store.failNextClosure = true

    svc.closeCourse(1L).value.attempt.flatMap {
      case Left(_) =>
        for
          c  <- courseRepo.getCourseById(1L)
          es <- enrollRepo.findEnrollmentByCourse(1L)
        yield
          // the single transaction rolled back: neither aggregate moved to CLOSED
          assertEquals(c.map(_.status), Some(CourseStatus.Active))
          assertEquals(es.map(_.status), List(EnrollmentStatus.Graded))
          assertEquals(events.closed, Nil)
      case Right(_) => fail("expected the closure commit to fail")
    }
  }
