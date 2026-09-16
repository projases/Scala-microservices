package edu.uoc.epcsd.course.patterns.freeapplicative

import java.time.LocalDate

import scala.concurrent.duration.*

import cats.Applicative
import cats.arrow.FunctionK
import cats.effect.{IO, Ref}
import cats.syntax.parallel.*

import edu.uoc.epcsd.course.domain.*
import edu.uoc.epcsd.course.service.Fakes.*

/** Step C: feel applicative-vs-monadic from the code.
  *
  *  `Programs.loadCourseData` is a `FreeApplicative` — its shape (one enrollments load, then a
  *  *static* fan-out of user loads) is known before it runs. That pays off three ways shown here:
  *   1. you can count and journal the loads without executing them;
  *   2. folded with the plain `IO` `Applicative`, the fan-out runs one-at-a-time;
  *   3. folded with a *parallel* `Applicative` (`ap` via `parMapN`), the same program's user
  *      loads run concurrently — proven by a max-in-flight metric, and contrasted with the
  *      monadic program, whose fan-out *must* wait for the enrollments and cannot be parallelized.
  */
class FreeApplicativeSuite extends munit.CatsEffectSuite:

  private val instructor = User(1L, "Prof", "instr@uoc.edu", "123", UserType.Instructor)
  private val student    = User(2L, "Alum", "student@uoc.edu", "456", UserType.Student)

  private val enrolled = List(
    Enrollment(10L, "student@uoc.edu", LocalDate.of(2026, 5, 1), 0L, EnrollmentStatus.Active, 1L),
    Enrollment(11L, "instr@uoc.edu",  LocalDate.of(2026, 5, 2), 0L, EnrollmentStatus.Active, 1L),
    Enrollment(12L, "ghost@uoc.edu",  LocalDate.of(2026, 5, 3), 0L, EnrollmentStatus.Active, 1L)
  )

  private val emails = List("student@uoc.edu", "instr@uoc.edu", "ghost@uoc.edu")

  private def newStore(): FakeStore =
    val s = new FakeStore
    s.enrollments = enrolled.toVector
    s

  /** Wraps any `UserService[IO]` and measures how many `getUserByEmail` calls are in flight at
    *  the same instant, widened with a sleep so the sequential/parallel difference is observable.
    */
  private final class TrackingUserService(
      delegate: UserService[IO],
      inFlight: Ref[IO, Int],
      maxInFlight: Ref[IO, Int]
  ) extends UserService[IO]:
    def getUserByEmail(email: String): IO[Option[User]] =
      for
        now <- inFlight.updateAndGet(_ + 1)
        _   <- maxInFlight.update(m => m.max(now))
        _   <- IO.sleep(150.millis)
        res <- delegate.getUserByEmail(email)
        _   <- inFlight.update(_ - 1)
      yield res

    def isInstructor(email: String): IO[Boolean] = delegate.isInstructor(email)

  /** A parallel `Applicative` for `IO` written out, so the "structure -> concurrent execution"
    *  step is explicit: `ap` runs both sides concurrently via `parMapN`. This is the behaviour of
    *  Cats Effect's `Parallel[IO].applicative` (over `IO.Par`).
    */
  private val parallelIO: Applicative[IO] =
    new Applicative[IO]:
        // pure lifts a value into the `IO` context, ap runs two IOs concurrently and applies the function result to the value result
      def pure[A](a: A): IO[A] = IO.pure(a)
      def ap[A, B](ff: IO[A => B])(fa: IO[A]): IO[B] =
        // explain this .. (ff, fa).parMapN(_(_)) runs the two IOs ff and fa concurrently, and when both complete, it applies the function result from ff to the value result from fa. The parMapN method is provided by Cats syntax for parallel operations, allowing you to combine two IOs in parallel and apply a function to their results. what does the first _ mean? The first underscore represents the function result from ff, and the second underscore represents the value result from fa. The expression _(_) is a shorthand for a function that takes two arguments: the first argument is the function result from ff, and the second argument is the value result from fa. It applies the function to the value, effectively calling the function with the value as its argument. In summary, (ff, fa).parMapN(_(_)) runs both IOs concurrently and applies the function result from ff to the value result from fa when both complete.
        // what is mapN? mapN is a method provided by Cats syntax that allows you to combine multiple effectful computations (in this case, IOs) and apply a function to their results. It takes a tuple of effectful computations and a function that takes the results of those computations as arguments. In this case, parMapN is used to run the two IOs concurrently and apply the function result from ff to the value result from fa when both complete.
        (ff, fa).parMapN(_(_))

  private def foldParallel[A](program: LoadProgram[A])(fk: FunctionK[LoadOp, IO]): IO[A] =
    program.foldMap(fk)(using parallelIO)

  test("the structure is inspectable without running: count folds the leaves") {
    val program = Programs.loadCourseData(1L, emails)
    assertEquals(Interpreters.count(program), 4) // 1 enrollment load + 3 user loads
  }

  test("the structure is inspectable without running: journal renders the fan-out") {
    val program  = Programs.loadCourseData(1L, emails)
    val journal = Interpreters.journal(program)
    assertEquals(journal.size, 4)
    assertEquals(journal.head, "GET courses/1/enrollments")
    assertEquals(
      journal.tail.toSet,
      Set("GET users/student@uoc.edu", "GET users/instr@uoc.edu", "GET users/ghost@uoc.edu")
    )
  }

  test("the monadic program must wait for the enrollments, so its fan-out runs one at a time") {
    val store     = newStore()
    val enrollRepo = new FakeEnrollmentRepo(store)
    for
      inFlight <- Ref.of[IO, Int](0)
      maxInFlight <- Ref.of[IO, Int](0)
      tracked  = new TrackingUserService(FakeUserService(List(student, instructor)), inFlight, maxInFlight)
      fk       = Interpreters.execute[IO](enrollRepo, tracked)
      result   <- Programs.loadCourseDataMonadic(1L).foldMap(fk)
      max      <- maxInFlight.get
    yield
      assertEquals(result._2.map(_.email), List("student@uoc.edu", "instr@uoc.edu"))
      assertEquals(max, 1)
  }

  test("the static FreeApplicative under the sequential IO Applicative also runs one at a time") {
    val store     = newStore()
    val enrollRepo = new FakeEnrollmentRepo(store)
    for
      inFlight <- Ref.of[IO, Int](0)
      maxInFlight <- Ref.of[IO, Int](0)
      tracked  = new TrackingUserService(FakeUserService(List(student, instructor)), inFlight, maxInFlight)
      fk       = Interpreters.execute[IO](enrollRepo, tracked)
      result   <- Programs.loadCourseData(1L, emails).foldMap(fk)
      max      <- maxInFlight.get
    yield
      assertEquals(result._2.map(_.email), List("student@uoc.edu", "instr@uoc.edu"))
      assertEquals(max, 1)
  }

  test("the SAME FreeApplicative under a parallel Applicative runs its fan-out concurrently") {
    val store     = newStore()
    val enrollRepo = new FakeEnrollmentRepo(store)
    for
      inFlight <- Ref.of[IO, Int](0)
      maxInFlight <- Ref.of[IO, Int](0)
      tracked  = new TrackingUserService(FakeUserService(List(student, instructor)), inFlight, maxInFlight)
      fk       = Interpreters.execute[IO](enrollRepo, tracked)
      result   <- foldParallel(Programs.loadCourseData(1L, emails))(fk)
      max      <- maxInFlight.get
    yield
      assertEquals(result._2.map(_.email), List("student@uoc.edu", "instr@uoc.edu"))
      assertEquals(max, 3)
  }
