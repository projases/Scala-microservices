CREATE TABLE IF NOT EXISTS alert (
    id         BIGSERIAL PRIMARY KEY,
    "from"     DATE NOT NULL,
    "to"       DATE NOT NULL,
    product_id BIGINT NOT NULL,
    user_id    BIGINT NOT NULL REFERENCES users (id)
);
