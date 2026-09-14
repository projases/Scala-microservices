package edu.uoc.epcsd.course.patterns.effectsasdata

import java.time.LocalDate

import cats.data.EitherT
import cats.effect.IO
import cats.effect.kernel.Clock
import munit.CatsEffectSuite

import edu.uoc.epcsd.course.domain.*
import edu.uoc.epcsd.course.domain.CourseError.*
import edu.uoc.epcsd.course.service.CreateCourse
import edu.uoc.epcsd.course.service.Fakes
import edu.uoc.epcsd.course.service.Fakes.FakeStore

/** Pure reducer tests (no `F`, no interpreters) plus shell tests that run the effects
  *  through the same fakes as `CourseServiceSuite`.
  */
class EffectsAsDataSuite extends munit.CatsEffectSuite:

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

  private def newStore(
      courses: List[Course] = Nil,
      enrollments: List[Enrollment] = Nil
  ): FakeStore =
    val s = new FakeStore
    s.courses = courses.toVector
    s.enrollments = enrollments.toVector
    s

  test("createCourse reducer: happy path yields one CreateCourse effect") {
    val t = Reducers.createCourse(req, Some(instructor), Nil)
    assertEquals(
      t.map(x => (x.next.status, x.next.title, x.effects)),
      Right((CourseStatus.Draft, "FP", List(Effect.CreateCourse(baseCourse.copy(id = None)))))
    )
  }

  test("createCourse reducer: rejects non-instructor and duplicate title") {
    assertEquals(Reducers.createCourse(req, None, Nil), Left(InstructorNotFound("instr@uoc.edu")))
    val existing = baseCourse.copy(id = Some(1L), title = "FP")
    assertEquals(
      Reducers.createCourse(req, Some(instructor), List(existing)),
      Left(DuplicateCourseTitle("FP"))
    )
  }

  test("createCourse reducer: rejects invalid dates") {
    val badDates = req.copy(
      enrollmentStartDate = LocalDate.of(2026, 7, 1),
      enrollmentEndDate = LocalDate.of(2026, 1, 1)
    )
    assertEquals(
      Reducers.createCourse(badDates, Some(instructor), Nil),
      Left(InvalidEnrollmentDates(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 1, 1)))
    )
  }

  test("openEnrollment reducer: DRAFT -> EnrollmentOpen, rejects ACTIVE") {
    val draft = baseCourse.copy(id = Some(1L))
    assertEquals(
      Reducers.openEnrollment(draft, start, end).map(_.effects),
      Right(List(Effect.UpdateCourse(draft.copy(status = CourseStatus.EnrollmentOpen))))
    )
    val active = draft.copy(status = CourseStatus.Active)
    assertEquals(
      Reducers.openEnrollment(active, start, end),
      Left(InvalidState("enrollment must be DRAFT to open", CourseStatus.Active.toString))
    )
  }

  test("closeEnrollment reducer: EnrollmentOpen -> Active") {
    val open = baseCourse.copy(id = Some(1L), status = CourseStatus.EnrollmentOpen)
    assertEquals(
      Reducers.closeEnrollment(open).map(_.effects),
      Right(List(Effect.UpdateCourse(open.copy(status = CourseStatus.Active))))
    )
  }

  test("enrollInCourse reducer: unknown user short-circuits before effects") {
    val open = baseCourse.copy(id = Some(1L), status = CourseStatus.EnrollmentOpen)
    assertEquals(
      Reducers.enrollInCourse(open, "ghost@uoc.edu", None, start),
      Left(UserNotFound("ghost@uoc.edu", isInstructor = false))
    )
    val t = Reducers.enrollInCourse(open, "student@uoc.edu", Some(student), LocalDate.of(2026, 5, 1))
    assertEquals(
      t.map(x => (x.next.student, x.next.enrollmentDate, x.next.courseId, x.effects)),
      Right(("student@uoc.edu", LocalDate.of(2026, 5, 1), 1L, List(Effect.CreateEnrollment(
        Enrollment(None, "student@uoc.edu", LocalDate.of(2026, 5, 1), 0L, EnrollmentStatus.Active, 1L)
      ))))
    )
  }

  test("closeGradeReports reducer: ACTIVE -> PendingClosure in the effect payload") {
    val active = baseCourse.copy(id = Some(1L), status = CourseStatus.Active)
    val draft  = baseCourse.copy(id = Some(1L))
    assertEquals(
      Reducers.closeGradeReports(draft, Nil),
      Left(InvalidState("course must be ACTIVE to close grade reports", CourseStatus.Draft.toString))
    )
    val graded = enrolled.copy(status = EnrollmentStatus.Graded)
    val t = Reducers.closeGradeReports(active, List(enrolled))
    assertEquals(
      t.map(_.effects),
      Right(List(Effect.PersistGradeClosure(
        active.copy(status = CourseStatus.PendingClosure), List(graded)
      )))
    )
  }

  test("closeCourse reducer: refuses to emit microcredential request when ungraded") {
    val active = baseCourse.copy(id = Some(1L), status = CourseStatus.Active)
    assertEquals(
      Reducers.closeCourse(active, List(enrolled)),
      Left(EnrollmentsNotGraded(1L))
    )
  }

  test("closeCourse reducer: graded -> Request, Persist, Publish in order") {
    val active     = baseCourse.copy(id = Some(1L), status = CourseStatus.Active)
    val graded     = List(enrolled.copy(status = EnrollmentStatus.Graded))
    val closed     = List(enrolled.copy(status = EnrollmentStatus.Closed))
    val courseTerm = active.copy(status = CourseStatus.Closed)
    assertEquals(
      Reducers.closeCourse(active, graded).map(_.effects),
      Right(List(
        Effect.RequestMicrocredentials(1L),
        Effect.PersistCourseClosure(courseTerm, closed),
        Effect.PublishCourseClosed(courseTerm)
      ))
    )
  }

  test("effect journal: describe renders the atomic transitions") {
    val c      = baseCourse.copy(id = Some(1L))
    val graded = List(enrolled.copy(status = EnrollmentStatus.Graded))
    assertEquals(
      Effect.PersistGradeClosure(c.copy(status = CourseStatus.PendingClosure), graded).describe,
      "1 TXN: course 1 -> PENDING_CLOSURE, 1 enrollments -> GRADED"
    )
    assertEquals(
      Effect.RequestMicrocredentials(1L).describe,
      "POST microcredentials/1/create (idempotent)"
    )
  }

  private final class CountingMicrocredSvc extends MicrocredentialService[IO]:
    var calls: List[Long] = Nil
    def requestMicrocredentials(courseId: Long): Eff[IO, Unit] =
      calls = calls :+ courseId
      EitherT.rightT(())

  test("shell: full lifecycle runs the effects end to end") {
    val store = newStore(courses = List(baseCourse.copy(id = Some(1L))))
    val svc = new EffectsAsDataShell[IO](
      Clock[IO],
      new Fakes.FakeCourseRepo(store),
      new Fakes.FakeEnrollmentRepo(store),
      Fakes.FakeUserService(List(instructor, student)),
      Fakes.FakeMicrocredentialSvc(),
      Fakes.FakeCourseEventPublisher()
    )
    val life = for
      _ <- svc.openEnrollment(1L, start, end)
      r <- svc.enrollInCourse(1L, "student@uoc.edu")
      _ <- svc.closeEnrollment(1L)
      _ <- svc.closeGradeReports(1L)
      _ <- svc.closeCourse(1L)
    yield r
    life.flatMap {
      case Right(_) =>
        val courseRepo = new Fakes.FakeCourseRepo(store)
        val enrollRepo = new Fakes.FakeEnrollmentRepo(store)
        for
          c  <- courseRepo.getCourseById(1L)
          es <- enrollRepo.findEnrollmentByCourse(1L)
        yield
          assertEquals(c.map(_.status), Some(CourseStatus.Closed))
          assertEquals(es.map(_.status), List(EnrollmentStatus.Closed))
      case Left(err) => fail(s"expected success, got $err")
    }
  }

  test("shell: microcredential failure leaves the store at ACTIVE and emits no event") {
    val active = baseCourse.copy(id = Some(1L), status = CourseStatus.Active)
    val store  = newStore(
      courses = List(active),
      enrollments = List(enrolled.copy(status = EnrollmentStatus.Graded))
    )
    val events = Fakes.FakeCourseEventPublisher()
    val svc = new EffectsAsDataShell[IO](
      Clock[IO],
      new Fakes.FakeCourseRepo(store),
      new Fakes.FakeEnrollmentRepo(store),
      Fakes.FakeUserService(List(instructor, student)),
      Fakes.FakeMicrocredentialSvc(fail = true),
      events
    )
    svc.closeCourse(1L).flatMap {
      case Left(MicrocredentialServiceUnavailable(_)) =>
        val courseRepo = new Fakes.FakeCourseRepo(store)
        courseRepo.getCourseById(1L).map { c =>
          assertEquals(c.map(_.status), Some(CourseStatus.Active))
          assertEquals(events.closed, Nil)
        }
      case other => fail(s"expected MicrocredentialServiceUnavailable, got $other")
    }
  }

  test("shell: closeCourse never calls the microcredential service when ungraded") {
    val active = baseCourse.copy(id = Some(1L), status = CourseStatus.Active)
    val store  = newStore(courses = List(active), enrollments = List(enrolled))
    val mc     = new CountingMicrocredSvc
    val svc = new EffectsAsDataShell[IO](
      Clock[IO],
      new Fakes.FakeCourseRepo(store),
      new Fakes.FakeEnrollmentRepo(store),
      Fakes.FakeUserService(List(instructor, student)),
      mc,
      Fakes.FakeCourseEventPublisher()
    )
    svc.closeCourse(1L).flatMap {
      case Left(EnrollmentsNotGraded(1L)) => IO.pure(assertEquals(mc.calls, Nil))
      case other                          => fail(s"expected EnrollmentsNotGraded, got $other")
    }
  }

  test("shell: createCourse persists and returns the generated id") {
    val store = newStore()
    val svc = new EffectsAsDataShell[IO](
      Clock[IO],
      new Fakes.FakeCourseRepo(store),
      new Fakes.FakeEnrollmentRepo(store),
      Fakes.FakeUserService(List(instructor)),
      Fakes.FakeMicrocredentialSvc(),
      Fakes.FakeCourseEventPublisher()
    )
    svc.createCourse(req).flatMap {
      case Right(id) =>
        IO.pure {
          assertEquals(id, 1L)
          assertEquals(store.courses.headOption.map(c => (c.id, c.status)), Some((Some(1L), CourseStatus.Draft)))
        }
      case Left(err) => fail(s"expected success, got $err")
    }
  }