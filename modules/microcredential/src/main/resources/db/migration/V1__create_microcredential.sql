CREATE TABLE IF NOT EXISTS microcredential (
    id             BIGSERIAL PRIMARY KEY,
    submitdate     TIMESTAMP NOT NULL,
    assignmentdate TIMESTAMP,
    status         VARCHAR NOT NULL,
    content        VARCHAR NOT NULL DEFAULT '',
    enrollment     BIGINT NOT NULL
);
