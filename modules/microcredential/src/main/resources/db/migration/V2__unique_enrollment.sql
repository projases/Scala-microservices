-- Enforce one microcredential per enrollment at the database level. This makes the
-- check-then-insert in requestCourseMicrocredentials atomic: two concurrent (or retried)
-- POST /microcredentials/{courseId}/create calls for the same enrollment cannot both
-- insert, so the course service's retry loop can't double-create microcredentials.
ALTER TABLE microcredential
    ADD CONSTRAINT uq_microcredential_enrollment UNIQUE (enrollment);
