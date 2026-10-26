-- Long-term episodic memory: what happened in the user's sessions (actions,
-- scenes, touches, failures), uploaded by the phone with consent. The phone
-- keeps only a short recent buffer; consolidation runs here, owner-scoped.
CREATE TABLE episodes (
    id VARCHAR(36) PRIMARY KEY,
    owner_user_id VARCHAR(36) NOT NULL REFERENCES users (id),
    entry_id VARCHAR(64) NOT NULL,
    device_id VARCHAR(128) NOT NULL,
    kind VARCHAR(16) NOT NULL,
    tag VARCHAR(64) NOT NULL,
    summary VARCHAR(500) NOT NULL,
    importance DOUBLE PRECISION NOT NULL,
    occurred_at TIMESTAMP,
    received_at TIMESTAMP NOT NULL,
    UNIQUE (owner_user_id, entry_id)
);

CREATE INDEX episodes_owner_kind_idx ON episodes (owner_user_id, kind, tag);
