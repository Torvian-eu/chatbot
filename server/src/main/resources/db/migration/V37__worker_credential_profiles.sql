-- Server-side source of truth for worker-bound credential profiles and encrypted secrets.
--
-- A profile names its secret by the `reference` string and deliberately holds no foreign key to
-- `worker_credential_secrets`: a hard reference would force either a cascade that deletes profiles the
-- operator did not ask to delete, or a delete-ordering rule, for a case that must stay operable.
-- Deleting a secret therefore leaves its referencing profiles in place; `attach` on such a profile
-- fails with a credential-unavailable error naming the reference.
--
-- A secret's `worker_id` is immutable after insert: the server stores ciphertext it cannot decrypt,
-- so it can never re-encrypt a secret for another worker.

CREATE TABLE worker_credential_profiles (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    worker_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    secret_ref VARCHAR(255) NOT NULL,
    command TEXT NOT NULL,
    args_json TEXT NOT NULL DEFAULT '[]',
    channel VARCHAR(32) NOT NULL,
    env_name VARCHAR(255),
    file_name VARCHAR(255),
    askpass_username VARCHAR(255),
    environment_json TEXT NOT NULL DEFAULT '{}',
    askpass_variables_json TEXT NOT NULL DEFAULT '[]',
    is_enabled BOOLEAN NOT NULL DEFAULT 1,
    created_at BIGINT NOT NULL DEFAULT (CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)),
    updated_at BIGINT NOT NULL DEFAULT (CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)),
    FOREIGN KEY (worker_id) REFERENCES workers (id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX worker_credential_profiles_worker_id_name_unique
    ON worker_credential_profiles (worker_id, name);

CREATE TABLE worker_credential_secrets (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    worker_id BIGINT NOT NULL,
    reference VARCHAR(255) NOT NULL,
    encrypted_secret TEXT NOT NULL,
    encrypted_dek TEXT NOT NULL,
    algorithm VARCHAR(128) NOT NULL,
    worker_certificate_fingerprint VARCHAR(128) NOT NULL,
    created_at BIGINT NOT NULL DEFAULT (CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)),
    updated_at BIGINT NOT NULL DEFAULT (CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)),
    FOREIGN KEY (worker_id) REFERENCES workers (id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX worker_credential_secrets_worker_id_reference_unique
    ON worker_credential_secrets (worker_id, reference);

-- One signature per (profile, device): a second write from the same device replaces its row, and the
-- raw payload is preserved so verification never depends on re-serialisation.
CREATE TABLE worker_credential_profile_signatures (
    profile_id BIGINT NOT NULL,
    user_device_id BIGINT NOT NULL,
    signature TEXT NOT NULL,
    timestamp BIGINT NOT NULL,
    nonce VARCHAR(255) NOT NULL,
    payload_json TEXT NOT NULL,
    created_at BIGINT NOT NULL DEFAULT (CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)),
    updated_at BIGINT NOT NULL DEFAULT (CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)),
    FOREIGN KEY (profile_id) REFERENCES worker_credential_profiles (id) ON DELETE CASCADE,
    FOREIGN KEY (user_device_id) REFERENCES user_devices (id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX worker_credential_profile_signatures_profile_id_user_device_id_unique
    ON worker_credential_profile_signatures (profile_id, user_device_id);
