package edu.uoc.epcsd.course.patterns.freeapplicative

import cats.Applicative
import cats.arrow.FunctionK
import cats.data.Const

import edu.uoc.epcsd.course.domain.*
import edu.uoc.epcsd.course.patterns.freeapplicative.LoadOp.*

/** Natural transformations for `FreeApplicative[LoadOp, _]` programs.
  *
  *  Because a FreeApplicative is a *structure* rather than a computation, it can be folded
  *  without executing anything (`count`, `journal`) just as easily as with a real effect
  *  (`execute`). Whichever fold you choose, each leaf is translated the same way — only the
  *  `Applicative` the structure is folded with changes.
  */
object Interpreters:

  /** Accumulator types for the static analyses: a `Const` ignores the program's `A` and just
    *  carries an accumulating payload (`Int` for counting, a `Vector[String]` for journaling).
    */
  private type CountAccumulator[A]    = Const[Int, A]
  private type JournalAccumulator[A]  = Const[Vector[String], A]

  /** Fold a program with a concrete effect: the pure `LoadOp -> F` translation over the real
    *  algebras. Used unchanged by every interpretation (sequential, parallel, in-memory).
    */
  def execute[F[_]](
      enrollmentRepo: EnrollmentRepository[F],
      userSvc: UserService[F]
  ): FunctionK[LoadOp, F] =
    new FunctionK[LoadOp, F]:
      def apply[A](op: LoadOp[A]): F[A] = op match
        case FindEnrollments(courseId) => enrollmentRepo.findEnrollmentByCourse(courseId)
        case FindUser(email)           => userSvc.getUserByEmail(email)

  /** Fold a program into a count of its loads, without touching a database. Each leaf counts 1;
    *  `pure` contributes 0 and `ap` adds the two sides — a `Const`/monoid-shaped analysis.
    */
  def count[A](program: LoadProgram[A]): Int =
    program
      .foldMap(new FunctionK[LoadOp, CountAccumulator]:
        def apply[B](op: LoadOp[B]): CountAccumulator[B] = Const(1)
      )
      .getConst

  /** Fold a program into a journal of its loads, in construction order, without executing. */
  def journal[A](program: LoadProgram[A]): Vector[String] =
    program
      .foldMap(new FunctionK[LoadOp, JournalAccumulator]:
        def apply[B](op: LoadOp[B]): JournalAccumulator[B] = Const(Vector(op.describe))
      )
      .getConst

  /** `Applicative`s for the analyses: `pure` contributes neutrals, `ap` combines with the
    *  payload's `+` (`Int`) or `++` (`Vector[String]`). Declared as given so the folds above pick
    *  them up by implicit resolution.
    */
  private given countApplicative: Applicative[CountAccumulator] =
    new Applicative[CountAccumulator]:
      def pure[A](a: A): CountAccumulator[A] = Const(0)
      def ap[A, B](ff: CountAccumulator[A => B])(fa: CountAccumulator[A]): CountAccumulator[B] =
        Const(ff.getConst + fa.getConst)

  private given journalApplicative: Applicative[JournalAccumulator] =
    new Applicative[JournalAccumulator]:
      def pure[A](a: A): JournalAccumulator[A] = Const(Vector.empty)
      def ap[A, B](ff: JournalAccumulator[A => B])(fa: JournalAccumulator[A]): JournalAccumulator[B] =
        Const(ff.getConst ++ fa.getConst)