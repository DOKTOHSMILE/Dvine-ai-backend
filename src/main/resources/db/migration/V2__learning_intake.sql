-- What phones learned offline and the user agreed to share. Owner-scoped,
-- reviewed before anything is promoted into a shared knowledge pack.
CREATE TABLE learning_intake (
    id VARCHAR(36) PRIMARY KEY,
    owner_user_id VARCHAR(36) NOT NULL REFERENCES users (id),
    entry_id VARCHAR(64) NOT NULL,
    device_id VARCHAR(128) NOT NULL,
    kind VARCHAR(24) NOT NULL,
    text VARCHAR(2000) NOT NULL,
    answer VARCHAR(2000) NOT NULL,
    client_created_at TIMESTAMP,
    status VARCHAR(16) NOT NULL,
    received_at TIMESTAMP NOT NULL,
    UNIQUE (owner_user_id, entry_id)
);

CREATE INDEX learning_intake_status_idx ON learning_intake (status, received_at);
