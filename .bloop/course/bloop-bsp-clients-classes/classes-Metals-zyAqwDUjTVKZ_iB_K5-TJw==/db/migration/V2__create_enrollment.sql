CREATE TABLE IF NOT EXISTS enrollment (
    id              BIGSERIAL PRIMARY KEY,
    student         VARCHAR NOT NULL,
    enrollmentdate  DATE    NOT NULL,
    qualification   BIGINT  NOT NULL,
    status          VARCHAR NOT NULL,
    course_id       BIGINT  NOT NULL REFERENCES course(id)
);
