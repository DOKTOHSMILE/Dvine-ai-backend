-- Code runs in the per-user Linux sandbox (owner-scoped, idempotent by the
-- phone's request id). Output is capped by the runner before it lands here.
CREATE TABLE workspace_runs (
    id VARCHAR(36) PRIMARY KEY,
    owner_user_id VARCHAR(36) NOT NULL REFERENCES users (id),
    request_id VARCHAR(80) NOT NULL,
    language VARCHAR(16) NOT NULL,
    code TEXT NOT NULL,
    network BOOLEAN NOT NULL,
    state VARCHAR(16) NOT NULL,
    exit_code INTEGER,
    stdout TEXT,
    stderr TEXT,
    timed_out BOOLEAN NOT NULL DEFAULT FALSE,
    duration_ms BIGINT,
    created_at TIMESTAMP NOT NULL,
    finished_at TIMESTAMP,
    UNIQUE (owner_user_id, request_id)
);

CREATE INDEX workspace_runs_owner_idx ON workspace_runs (owner_user_id, created_at);
