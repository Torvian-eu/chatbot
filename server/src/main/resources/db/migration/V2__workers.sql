CREATE TABLE workers (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    owner_user_id BIGINT NOT NULL,
    worker_uid VARCHAR(64) NOT NULL,
    display_name VARCHAR(255) NOT NULL,
    certificate_pem TEXT NOT NULL,
    certificate_fingerprint VARCHAR(255) NOT NULL,
    allowed_scopes_json TEXT NOT NULL DEFAULT '[]',
    created_at BIGINT NOT NULL,
    last_seen_at BIGINT,
    FOREIGN KEY (owner_user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX workers_worker_uid_unique ON workers (worker_uid);
CREATE UNIQUE INDEX workers_certificate_fingerprint_unique ON workers (certificate_fingerprint);
CREATE INDEX workers_owner_user_id_idx ON workers (owner_user_id);

CREATE TABLE worker_auth_challenges (
    challenge_id VARCHAR(64) PRIMARY KEY,
    worker_id BIGINT NOT NULL,
    challenge TEXT NOT NULL,
    expires_at BIGINT NOT NULL,
    consumed_at BIGINT,
    created_at BIGINT NOT NULL,
    FOREIGN KEY (worker_id) REFERENCES workers (id) ON DELETE CASCADE
);

CREATE INDEX worker_auth_challenges_worker_idx ON worker_auth_challenges (worker_id, expires_at);



