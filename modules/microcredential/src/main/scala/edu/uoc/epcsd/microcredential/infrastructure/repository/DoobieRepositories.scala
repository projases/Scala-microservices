package edu.uoc.epcsd.microcredential.infrastructure.repository

import java.time.Instant

import cats.effect.MonadCancel
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import doobie.util.transactor.Transactor

import edu.uoc.epcsd.microcredential.domain.*

object DoobieMappings:
  private def toEnumName(snake: String): String = Codecs.fromUpperSnakeCase(snake)
  given Meta[MicrocredentialStatus] =
    Meta[String].timap(s => MicrocredentialStatus.valueOf(toEnumName(s)))(c => Codecs.toUpperSnakeCase(c.toString))

import DoobieMappings.given

class DoobieMicrocredentialRepository[F[_]](xa: Transactor[F])(using F: MonadCancel[F, Throwable])
    extends MicrocredentialRepository[F]:

  private type Row = (Long, Instant, Option[Instant], MicrocredentialStatus, String, Long)

  private def fromRow(r: Row): Microcredential =
    Microcredential(r._1, r._2, r._3, r._4, r._5, r._6)

  def getById(id: Long): F[Option[Microcredential]] =
    sql"""SELECT id, submitdate, assignmentdate, status, content, enrollment
          FROM microcredential WHERE id = $id"""
      .query[Row]
      .map(fromRow)
      .option
      .transact(xa)

  def getByEnrollment(enrollmentId: Long): F[Option[Microcredential]] =
    sql"""SELECT id, submitdate, assignmentdate, status, content, enrollment
          FROM microcredential WHERE enrollment = $enrollmentId"""
      .query[Row]
      .map(fromRow)
      .option
      .transact(xa)

  def create(m: Microcredential): F[Microcredential] =
    sql"""INSERT INTO microcredential (submitdate, assignmentdate, status, content, enrollment)
          VALUES (${m.submitDate}, ${m.assignmentDate}, ${m.status}, ${m.content}, ${m.enrollment})"""
      .update
      .withUniqueGeneratedKeys[Long]("id")
      .transact(xa)
      .map(id => m.copy(id = id))

  def update(m: Microcredential): F[Unit] =
    sql"""UPDATE microcredential SET
            submitdate=${m.submitDate}, assignmentdate=${m.assignmentDate},
            status=${m.status}, content=${m.content}, enrollment=${m.enrollment}
          WHERE id = ${m.id}"""
      .update
      .run
      .transact(xa)
      .void

  def getPendingRequests: F[List[Microcredential]] =
    sql"""SELECT id, submitdate, assignmentdate, status, content, enrollment
          FROM microcredential WHERE status = 'REQUESTED'"""
      .query[Row]
      .map(fromRow)
      .to[List]
      .transact(xa)
