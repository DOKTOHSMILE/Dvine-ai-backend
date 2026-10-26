-- A phone registers once and gets its own secret; it trades that secret for
-- short-lived session tokens. Only a hash of the secret is stored.
CREATE TABLE devices (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL REFERENCES users (id),
    device_id VARCHAR(128) NOT NULL UNIQUE,
    secret_hash CHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    revoked_at TIMESTAMP
);
