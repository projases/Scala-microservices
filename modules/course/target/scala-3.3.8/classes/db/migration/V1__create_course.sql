CREATE TABLE IF NOT EXISTS course (
    id                   BIGSERIAL PRIMARY KEY,
    instructor           VARCHAR NOT NULL,
    title                VARCHAR NOT NULL,
    description          VARCHAR NOT NULL,
    enrollmentstartdate  DATE    NOT NULL,
    enrollmentenddate    DATE    NOT NULL,
    mode                 VARCHAR NOT NULL,
    price                BIGINT  NOT NULL,
    objectives           VARCHAR NOT NULL,
    methology            VARCHAR NOT NULL,
    duration             BIGINT  NOT NULL,
    language             VARCHAR NOT NULL,
    location             VARCHAR NOT NULL,
    status               VARCHAR NOT NULL
);
