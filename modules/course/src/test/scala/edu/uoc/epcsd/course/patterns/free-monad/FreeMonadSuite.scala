package edu.uoc.epcsd.course.patterns.freemonad

import java.time.LocalDate

import cats.effect.IO
import cats.effect.kernel.Clock
import munit.CatsEffectSuite

import edu.uoc.epcsd.course.domain.*
import edu.uoc.epcsd.course.domain.CourseError.*
import edu.uoc.epcsd.course.service.CreateCourse
import edu.uoc.epcsd.course.service.Fakes.*
import edu.uoc.epcsd.course.service.Fakes.FakeStore

/** Behavioural tests for the Free monad programs, run through the SAME `DoobieInterpreter` that
  *  production wires against doobie — here backed by `service.Fakes` (the in-memory interpreter) —
  *  plus instruction-stream assertions via `LoggingInterpreter` (the third interpreter). The
  *  instruction script is the initial-encoding payoff: `closeCourse` is a *value* you can journal.
  */
class FreeMonadSuite extends munit.CatsEffectSuite:

  private val instructor = User(1L, "Prof", "instr@uoc.edu", "123", UserType.Instructor)
  private val student    = User(2L, "Alum", "student@uoc.edu", "456", UserType.Student)

  private val start = LocalDate.of(2026, 1, 1)
  private val end   = LocalDate.of(2026, 6, 30)

  private val baseCourse = Course(
    id = None, instructor = "instr@uoc.edu",
    title = "FP", description = "desc",
    enrollmentStartDate = start, enrollmentEndDate = end,
    mode = "Online", price = 100, objectives = "o", methology = "m",
    duration = 40, language = "en", location = "web", status = CourseStatus.Draft
  )

  private val req = CreateCourse(
    "instr@uoc.edu", "FP", "desc", start, end, "Online", 100, "o", "m", 40, "en", "web"
  )

  private val enrolled =
    Enrollment(Some(10L), "student@uoc.edu", LocalDate.of(2026, 5, 1), 0L, EnrollmentStatus.Active, 1L)

  private def clockToday: IO[LocalDate] =
    Clock[IO].realTimeInstant.map(i => LocalDate.ofInstant(i, java.time.ZoneOffset.UTC))

  private def newStore(
      courses: List[Course] = Nil,
      enrollments: List[Enrollment] = Nil
  ): FakeStore =
    val s = new FakeStore
    s.courses = courses.toVector
    s.enrollments = enrollments.toVector
    s

  /** The in-memory interpreter: the polymorphic `DoobieInterpreter` over `service.Fakes`. */
  private def interp(
      store: FakeStore,
      users: List[User] = Nil,
      mc: MicrocredentialService[IO] = FakeMicrocredentialSvc(),
      events: CourseEventPublisher[IO] = FakeCourseEventPublisher()
  ): DoobieInterpreter[IO] =
    new DoobieInterpreter[IO](
      Clock[IO],
      new FakeCourseRepo(store),
      new FakeEnrollmentRepo(store),
      FakeUserService(users),
      mc,
      events
    )

  test("createCourse: happy path returns the generated id and journals three instructions") {
    val store   = newStore()
    val logging = new LoggingInterpreter(interp(store, List(instructor)))
    Lifecycle.run(Lifecycle.createCourse(req))(logging).flatMap {
      case Right(id) => IO.pure {
        assertEquals(id, 1L)
        assertEquals(
          logging.instructions,
          List("GET isInstructor(instr@uoc.edu)", "GET courses", "INSERT course 'FP'")
        )
        assertEquals(store.courses.headOption.map(c => (c.id, c.status)), Some((Some(1L), CourseStatus.Draft)))
      }
      case Left(err) => fail(s"expected success, got $err")
    }
  }

  test("createCourse: rejects a non-instructor email") {
    val store = newStore()
    Lifecycle.run(Lifecycle.createCourse(req))(interp(store, List(student))).flatMap {
      case Left(InstructorNotFound(email)) => IO.pure(assertEquals(email, "instr@uoc.edu"))
      case other => IO(fail(s"expected InstructorNotFound, got $other"))
    }
  }

  test("createCourse: rejects a duplicate title") {
    val store  = newStore(courses = List(baseCourse.copy(id = Some(1L), title = "FP")))
    Lifecycle.run(Lifecycle.createCourse(req))(interp(store, List(instructor))).flatMap {
      case Left(DuplicateCourseTitle(t)) => IO.pure(assertEquals(t, "FP"))
      case other => IO(fail(s"expected DuplicateCourseTitle, got $other"))
    }
  }

  test("createCourse: rejects invalid dates before issuing any instruction") {
    val store = newStore()
    val logging = new LoggingInterpreter(interp(store, List(instructor)))
    val badDate = req.copy(
      enrollmentStartDate = LocalDate.of(2026, 7, 1),
      enrollmentEndDate = LocalDate.of(2026, 1, 1)
    )
    Lifecycle.run(Lifecycle.createCourse(badDate))(logging).flatMap {
      case Left(InvalidEnrollmentDates(a, b)) => IO.pure {
        assertEquals((a, b), (LocalDate.of(2026, 7, 1), LocalDate.of(2026, 1, 1)))
        assertEquals(logging.instructions, Nil)
      }
      case other => IO(fail(s"expected InvalidEnrollmentDates, got $other"))
    }
  }

  test("openEnrollment: DRAFT -> EnrollmentOpen; rejects ACTIVE") {
    val draft = baseCourse.copy(id = Some(1L))
    val store = newStore(courses = List(draft))
    val logging = new LoggingInterpreter(interp(store, Nil))
    Lifecycle.run(Lifecycle.openEnrollment(1L, start, end))(logging).flatMap {
      case Right(_) => IO.pure {
        assertEquals(
          logging.instructions,
          List("GET courses/1", "UPDATE course 1 -> ENROLLMENT_OPEN")
        )
        assertEquals(store.courses.headOption.map(_.status), Some(CourseStatus.EnrollmentOpen))
      }
      case Left(err) => fail(s"expected success, got $err")
    }
    val active = draft.copy(status = CourseStatus.Active)
    Lifecycle.run(Lifecycle.openEnrollment(1L, start, end))(interp(newStore(courses = List(active)))).flatMap {
      case Left(InvalidState(expected, actual)) => IO.pure {
        assertEquals(expected, "enrollment must be DRAFT to open")
        assertEquals(actual, CourseStatus.Active.toString)
      }
      case other => IO(fail(s"expected InvalidState, got $other"))
    }
  }

  test("closeEnrollment: EnrollmentOpen -> Active") {
    val open  = baseCourse.copy(id = Some(1L), status = CourseStatus.EnrollmentOpen)
    val store = newStore(courses = List(open))
    Lifecycle.run(Lifecycle.closeEnrollment(1L))(interp(store, Nil)).flatMap {
      case Right(_) => courseStatus(store, 1L).map(s => assertEquals(s, Some(CourseStatus.Active)))
      case Left(err) => fail(s"expected success, got $err")
    }
  }

  test("enrollInCourse: unknown user short-circuits before persisting") {
    val open  = baseCourse.copy(id = Some(1L), status = CourseStatus.EnrollmentOpen)
    val store = newStore(courses = List(open))
    val logging = new LoggingInterpreter(interp(store, Nil))
    Lifecycle.run(Lifecycle.enrollInCourse(1L, "ghost@uoc.edu"))(logging).flatMap {
      case Left(UserNotFound(email, _)) => IO.pure {
        assertEquals(email, "ghost@uoc.edu")
        assertEquals(logging.instructions, List("GET courses/1", "GET users/ghost@uoc.edu"))
        assertEquals(store.enrollments, Vector.empty)
      }
      case other => IO(fail(s"expected UserNotFound, got $other"))
    }
  }

  test("enrollInCourse: happy path creates an Active enrollment on the enrollment date") {
    val open  = baseCourse.copy(id = Some(1L), status = CourseStatus.EnrollmentOpen)
    val store = newStore(courses = List(open))
    Lifecycle.run(Lifecycle.enrollInCourse(1L, "student@uoc.edu"))(interp(store, List(student))).flatMap {
      case Right(_) => enrollmentRepo(store).findEnrollmentByCourse(1L).flatMap { es =>
        clockToday.map { today =>
          assertEquals(
            es.map(e => (e.student, e.enrollmentDate, e.status)),
            List(("student@uoc.edu", today, EnrollmentStatus.Active))
          )
        }
      }
      case Left(err) => fail(s"expected success, got $err")
    }
  }

  test("closeGradeReports: ACTIVE -> PendingClosure; rejects DRAFT") {
    val active = baseCourse.copy(id = Some(1L), status = CourseStatus.Active)
    val store  = newStore(courses = List(active), enrollments = List(enrolled))
    val logging = new LoggingInterpreter(interp(store, Nil))
    Lifecycle.run(Lifecycle.closeGradeReports(1L))(logging).flatMap {
      case Right(_) => IO.pure {
        assertEquals(
          logging.instructions,
          List("GET courses/1", "GET courses/1/enrollments", "1 TXN: course 1 -> PENDING_CLOSURE, 1 enrollments -> GRADED")
        )
        assertEquals(store.courses.headOption.map(_.status), Some(CourseStatus.PendingClosure))
        assertEquals(store.enrollments.headOption.map(_.status), Some(EnrollmentStatus.Graded))
      }
      case Left(err) => fail(s"expected success, got $err")
    }
    val draft = baseCourse.copy(id = Some(1L))
    Lifecycle.run(Lifecycle.closeGradeReports(1L))(interp(newStore(courses = List(draft)))).flatMap {
      case Left(InvalidState(expected, actual)) => IO.pure {
        assertEquals(expected, "course must be ACTIVE to close grade reports")
        assertEquals(actual, CourseStatus.Draft.toString)
      }
      case other => IO(fail(s"expected InvalidState, got $other"))
    }
  }

  test("closeCourse: guarded before any microcredential call when ungraded") {
    val active = baseCourse.copy(id = Some(1L), status = CourseStatus.Active)
    val store  = newStore(courses = List(active), enrollments = List(enrolled))
    val logging = new LoggingInterpreter(interp(store, List(instructor, student)))
    Lifecycle.run(Lifecycle.closeCourse(1L))(logging).flatMap {
      case Left(EnrollmentsNotGraded(courseId)) => IO.pure {
        assertEquals(courseId, 1L)
        // the script stops at the load — Request/Persist/Publish are never even constructed
        assertEquals(logging.instructions, List("GET courses/1", "GET courses/1/enrollments"))
      }
      case other => IO(fail(s"expected EnrollmentsNotGraded, got $other"))
    }
  }

  test("closeCourse: graded journals Request, Persist, Publish in order") {
    val active = baseCourse.copy(id = Some(1L), status = CourseStatus.Active)
    val graded = List(enrolled.copy(status = EnrollmentStatus.Graded))
    val store  = newStore(courses = List(active), enrollments = graded)
    val logging = new LoggingInterpreter(interp(store, List(instructor, student)))
    Lifecycle.run(Lifecycle.closeCourse(1L))(logging).flatMap {
      case Right(_) => IO.pure {
        assertEquals(
          logging.instructions,
          List(
            "GET courses/1",
            "GET courses/1/enrollments",
            "POST microcredentials/1/create (idempotent)",
            "1 TXN: course 1 -> CLOSED, 1 enrollments -> CLOSED",
            "ENQUEUE course.closed for course 1 (async)"
          )
        )
        assertEquals(store.courses.headOption.map(_.status), Some(CourseStatus.Closed))
        assertEquals(store.enrollments.toList.map(_.status), List(EnrollmentStatus.Closed))
      }
      case Left(err) => fail(s"expected success, got $err")
    }
  }

  test("closeCourse: microcredential failure stops the script before Persist/Publish and leaves the store untouched") {
    val active = baseCourse.copy(id = Some(1L), status = CourseStatus.Active)
    val graded = List(enrolled.copy(status = EnrollmentStatus.Graded))
    val store  = newStore(courses = List(active), enrollments = graded)
    val events = FakeCourseEventPublisher()
    val logging = new LoggingInterpreter(
      interp(store, List(instructor, student), FakeMicrocredentialSvc(fail = true), events)
    )
    Lifecycle.run(Lifecycle.closeCourse(1L))(logging).flatMap {
      case Left(MicrocredentialServiceUnavailable(_)) =>
        enrollmentRepo(store).findEnrollmentByCourse(1L).map { es =>
          assertEquals(logging.instructions, List(
            "GET courses/1",
            "GET courses/1/enrollments",
            "POST microcredentials/1/create (idempotent)"
          ))
          assertEquals(store.courses.headOption.map(_.status), Some(CourseStatus.Active))
          assertEquals(es.map(_.status), List(EnrollmentStatus.Graded))
          assertEquals(events.closed, Nil)
        }
      case other => IO(fail(s"expected MicrocredentialServiceUnavailable, got $other"))
    }
  }

  test("full lifecycle: open -> enroll -> close -> grade -> close runs end to end") {
    val store  = newStore(courses = List(baseCourse.copy(id = Some(1L))))
    val events = FakeCourseEventPublisher()
    val interpPublishing = interp(store, List(instructor, student), events = events)
    val life = for
      _ <- Lifecycle.run(Lifecycle.openEnrollment(1L, start, end))(interpPublishing)
      _ <- Lifecycle.run(Lifecycle.enrollInCourse(1L, "student@uoc.edu"))(interpPublishing)
      _ <- Lifecycle.run(Lifecycle.closeEnrollment(1L))(interpPublishing)
      _ <- Lifecycle.run(Lifecycle.closeGradeReports(1L))(interpPublishing)
      r <- Lifecycle.run(Lifecycle.closeCourse(1L))(interpPublishing)
    yield r
    life.flatMap {
      case Right(_) => enrollmentRepo(store).findEnrollmentByCourse(1L).map { es =>
        assertEquals(store.courses.headOption.map(_.status), Some(CourseStatus.Closed))
        assertEquals(es.map(_.status), List(EnrollmentStatus.Closed))
        assertEquals(events.closed.map(_.courseId), List(1L))
      }
      case Left(err) => fail(s"expected success, got $err")
    }
  }

  test("instruction journal: describe renders the atomic transitions") {
    val c      = baseCourse.copy(id = Some(1L))
    val graded = List(enrolled.copy(status = EnrollmentStatus.Graded))
    assertEquals(
      CourseOp.PersistGradeClosure(c.copy(status = CourseStatus.PendingClosure), graded).describe,
      "1 TXN: course 1 -> PENDING_CLOSURE, 1 enrollments -> GRADED"
    )
    assertEquals(
      CourseOp.RequestMicrocredentials(1L).describe,
      "POST microcredentials/1/create (idempotent)"
    )
  }

  private def courseStatus(store: FakeStore, id: Long): IO[Option[CourseStatus]] =
    courseRepo(store).getCourseById(id).map(_.map(_.status))

  private def courseRepo(store: FakeStore)  = new FakeCourseRepo(store)
  private def enrollmentRepo(store: FakeStore) = new FakeEnrollmentRepo(store)