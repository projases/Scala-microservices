CREATE TABLE IF NOT EXISTS users (
    id           BIGSERIAL PRIMARY KEY,
    full_name    VARCHAR NOT NULL,
    email        VARCHAR NOT NULL UNIQUE,
    password     VARCHAR NOT NULL,
    phone_number VARCHAR NOT NULL,
    type         VARCHAR NOT NULL DEFAULT 'STUDENT'
);
