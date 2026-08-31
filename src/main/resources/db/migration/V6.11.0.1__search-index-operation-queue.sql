CREATE TABLE IF NOT EXISTS searchindexoperation (
    id BIGSERIAL PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    backend VARCHAR(32) NOT NULL,
    operationtype VARCHAR(32) NOT NULL,
    state VARCHAR(32) NOT NULL,
    payload TEXT NOT NULL,
    attemptcount INTEGER NOT NULL DEFAULT 0,
    createdat TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    nextattemptat TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    leaseuntil TIMESTAMP WITHOUT TIME ZONE,
    lasterror TEXT
);

CREATE INDEX IF NOT EXISTS index_searchindexoperation_backend_id
    ON searchindexoperation (backend, id);
