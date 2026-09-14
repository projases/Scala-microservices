package edu.uoc.epcsd.course.patterns.effectsasdata

import java.time.LocalDate

import cats.Monad
import cats.data.EitherT
import cats.effect.Clock
import cats.syntax.all.*

import edu.uoc.epcsd.course.domain.*
import edu.uoc.epcsd.course.domain.Eff
import edu.uoc.epcsd.course.service.CreateCourse

/** The imperative shell of Effects-as-Data.
  *
  *  Compared to `CourseService` (tagless-final): the workflow logic lives in `Reducers`,
  *  which never touch `F`. This shell only loads whatever the reducer asks for, runs it, and
  *  executes the returned `Effect`s. Where `CourseService` calls `microcredSvc` *before* the
  *  "all graded" check, here the reducer refuses to emit `RequestMicrocredentials` from a state
  *  that is doomed — the close never touches the outside world unless it can succeed.
  */
class EffectsAsDataShell[F[_]: Monad](
    clock: Clock[F],
    courseRepo: CourseRepository[F],
    enrollmentRepo: EnrollmentRepository[F],
    userSvc: UserService[F],
    microcredSvc: MicrocredentialService[F],
    eventPublisher: CourseEventPublisher[F]
):

  def createCourse(req: CreateCourse): F[Either[CourseError, Long]] =
    for
      instructor <- userSvc.getUserByEmail(req.instructor)
      existing   <- courseRepo.findCourses
      result     <- Reducers.createCourse(req, instructor, existing) match
        case Left(err) => Monad[F].pure(Left(err))
        case Right(t)  => courseRepo.createCourse(t.next).map(c => Right(idOf(c)))
    yield result

  def openEnrollment(courseId: Long, start: LocalDate, end: LocalDate): F[Either[CourseError, Unit]] =
    for
      course <- getCourseOrFail(courseId)
      result <- course match
        case Left(err)      => Monad[F].pure(Left(err))
        case Right(c)       => run(Reducers.openEnrollment(c, start, end))
    yield result

  def closeEnrollment(courseId: Long): F[Either[CourseError, Unit]] =
    for
      course <- getCourseOrFail(courseId)
      result <- course match
        case Left(err) => Monad[F].pure(Left(err))
        case Right(c)  => run(Reducers.closeEnrollment(c))
    yield result

  def enrollInCourse(courseId: Long, email: String): F[Either[CourseError, Unit]] =
    for
      course <- getCourseOrFail(courseId)
      user   <- userSvc.getUserByEmail(email)
      now    <- today
      result <- course match
        case Left(err) => Monad[F].pure(Left(err))
        case Right(c)  => run(Reducers.enrollInCourse(c, email, user, now))
    yield result

  def closeGradeReports(courseId: Long): F[Either[CourseError, Unit]] =
    for
      course <- getCourseOrFail(courseId)
      result <- course match
        case Left(err) => Monad[F].pure(Left(err))
        case Right(c)  =>
          enrollmentRepo.findEnrollmentByCourse(courseId).flatMap { enrollments =>
            run(Reducers.closeGradeReports(c, enrollments))
          }
    yield result

  def closeCourse(courseId: Long): F[Either[CourseError, Unit]] =
    for
      course <- getCourseOrFail(courseId)
      result <- course match
        case Left(err) => Monad[F].pure(Left(err))
        case Right(c)  =>
          enrollmentRepo.findEnrollmentByCourse(courseId).flatMap { enrollments =>
            run(Reducers.closeCourse(c, enrollments))
          }
    yield result

  private def run[A](t: Either[CourseError, Transition[A]]): F[Either[CourseError, Unit]] =
    t match
      case Left(err) => Monad[F].pure(Left(err))
      case Right(tr) => tr.effects.traverse_(interpret).value

  private def interpret(e: Effect): Eff[F, Unit] = e match
    case Effect.CreateCourse(c)             => EitherT.liftF(courseRepo.createCourse(c).void)
    case Effect.UpdateCourse(c)             => EitherT.liftF(courseRepo.updateCourse(c))
    case Effect.CreateEnrollment(en)        => EitherT.liftF(enrollmentRepo.createEnrollment(en).void)
    case Effect.PersistGradeClosure(c, es)  => EitherT.liftF(courseRepo.persistGradeReportClosure(c, es))
    case Effect.PersistCourseClosure(c, es) => EitherT.liftF(courseRepo.persistCourseClosure(c, es))
    case Effect.RequestMicrocredentials(id) => microcredSvc.requestMicrocredentials(id)
    case Effect.PublishCourseClosed(c)      => EitherT.liftF(eventPublisher.publishClosed(c))

  private def getCourseOrFail(courseId: Long): F[Either[CourseError, Course]] =
    courseRepo.getCourseById(courseId).map(_.toRight(CourseError.CourseNotFound(courseId)))

  private def today: F[LocalDate] =
    clock.realTimeInstant.map(i => LocalDate.ofInstant(i, java.time.ZoneOffset.UTC))

  private def idOf(c: Course): Long = c.id.getOrElse(
    throw new IllegalStateException("CreateCourse must persist a Course with an id")
  )