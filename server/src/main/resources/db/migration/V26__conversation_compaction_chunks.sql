-- Automated conversation compaction: persisted full-thread summary chunks.
--
-- Each row in conversation_compaction_chunks is one immutable full-thread summary created for a
-- chat session when the estimated primary LLM input exceeded the user's configured threshold.
-- Chunk rows are deliberately never deleted or flagged as superseded: whether a chunk is usable
-- for a given context is a branch-relative selection rule (all covered message IDs present in the
-- current parent chain with matching timestamps, newest overlapping chunk wins), not a stored flag.
--
-- Provenance: model_id/settings_id/provider_id are nullable FKs with ON DELETE SET NULL so that
-- deleting the referenced rows neither blocks the deletion nor cascades away the chunk. The
-- immutable *_name snapshots and the exact instruction snapshot survive those deletions for audit
-- and reproducibility. token_counter_version records the estimate formula used for the persisted
-- source/result counts (v1: approx_utf16_json_v1).

CREATE TABLE conversation_compaction_chunks (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id BIGINT NOT NULL,
    summary TEXT NOT NULL,
    model_id BIGINT,
    settings_id BIGINT,
    provider_id BIGINT,
    model_name VARCHAR(255) NOT NULL,
    settings_name VARCHAR(255) NOT NULL,
    provider_name VARCHAR(255) NOT NULL,
    instruction TEXT NOT NULL,
    threshold_tokens BIGINT NOT NULL,
    source_token_count BIGINT NOT NULL,
    result_token_count BIGINT NOT NULL,
    token_counter_version VARCHAR(50) NOT NULL,
    coverage_count INTEGER NOT NULL,
    created_at BIGINT NOT NULL,
    FOREIGN KEY (session_id) REFERENCES chat_sessions (id) ON DELETE CASCADE,
    FOREIGN KEY (model_id) REFERENCES llm_models (id) ON DELETE SET NULL,
    FOREIGN KEY (settings_id) REFERENCES model_settings (id) ON DELETE SET NULL,
    FOREIGN KEY (provider_id) REFERENCES llm_providers (id) ON DELETE SET NULL
);

CREATE INDEX conversation_compaction_chunks_session_created_idx
    ON conversation_compaction_chunks (session_id, created_at DESC, id DESC);

-- Ordered message-ID coverage of one chunk: ordinal positions correspond to the chronological
-- source-unit order of the thread that was summarized, message_id identifies the covered original
-- ChatMessage, and observed_updated_at is the epoch-millisecond timestamp observed at creation.
--
-- There is deliberately NO foreign key from message_id to chat_messages: deleting a covered message
-- must neither block the deletion (RESTRICT) nor silently cascade away part of an immutable
-- historical snapshot (CASCADE). A missing ID naturally makes the chunk ineligible, while session
-- deletion removes the whole chunk through conversation_compaction_chunks.session_id.

CREATE TABLE conversation_compaction_chunk_messages (
    chunk_id BIGINT NOT NULL,
    ordinal INTEGER NOT NULL,
    message_id BIGINT NOT NULL,
    observed_updated_at BIGINT NOT NULL,
    PRIMARY KEY (chunk_id, ordinal),
    UNIQUE (chunk_id, message_id),
    FOREIGN KEY (chunk_id) REFERENCES conversation_compaction_chunks (id) ON DELETE CASCADE
);

CREATE INDEX conversation_compaction_chunk_messages_message_idx
    ON conversation_compaction_chunk_messages (message_id);
