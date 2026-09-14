package edu.uoc.epcsd.course.patterns.effectsasdata

import edu.uoc.epcsd.course.domain.{Course, Enrollment}

/** The execution vocabulary of Effects-as-Data.
  *
  *  A reducer never *runs* these — it returns them as data. The imperative shell turns
  *  each one back into a real call (doobie write, REST call, queue enqueue).
  */
enum Effect:
  case CreateCourse(c: Course)
  case UpdateCourse(c: Course)
  case CreateEnrollment(e: Enrollment)
  case PersistGradeClosure(c: Course, gradedEnrollments: List[Enrollment])
  case PersistCourseClosure(c: Course, closedEnrollments: List[Enrollment])
  case RequestMicrocredentials(courseId: Long)
  case PublishCourseClosed(c: Course)

  /** A one-line, human-readable journal of what this effect would do. */
  def describe: String = this match
    case CreateCourse(c)              => s"INSERT course '${c.title}'"
    case UpdateCourse(c)              => s"UPDATE course ${idOf(c)} -> ${c.status}"
    case CreateEnrollment(e)          => s"INSERT enrollment for ${e.student}"
    case PersistGradeClosure(c, es)   => s"1 TXN: course ${idOf(c)} -> PENDING_CLOSURE, ${es.size} enrollments -> GRADED"
    case PersistCourseClosure(c, es)  => s"1 TXN: course ${idOf(c)} -> CLOSED, ${es.size} enrollments -> CLOSED"
    case RequestMicrocredentials(cid) => s"POST microcredentials/$cid/create (idempotent)"
    case PublishCourseClosed(c)       => s"ENQUEUE course.closed for course ${idOf(c)} (async)"

  private def idOf(c: Course): String = c.id.fold("<unsaved>")(_.toString)

/** The outcome of a reducer: the next state, plus the effects that must run to reach it. */
final case class Transition[A](next: A, effects: List[Effect]):
  def map[B](f: A => B): Transition[B] = Transition(f(next), effects)