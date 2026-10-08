-- Per-preset conversation-compaction configuration.
--
-- Conversation compaction used to be governed exclusively by the per-user `conversation_compaction`
-- preference. It now also depends on the preset a turn runs with, which is the unit that actually
-- drives an agent-role turn:
--   * `compaction_enabled` is a constraint ANDed with the preference: a preset with
--     `compaction_enabled = 0` never compacts its sessions (and does not even read the preference),
--     while `compaction_enabled = 1` still requires a present, enabled preference — because the
--     preference remains the source of the auxiliary summarization model, its settings, instruction,
--     system message and summary label.
--   * `compaction_threshold_tokens` is an optional override: when non-null it is the compaction
--     threshold in input tokens for that preset, and when NULL the preference's own threshold applies
--     (100000 when the preference omits it). A stored value is always >= 1 (0 and negative values are
--     rejected on both write paths, and 0 is the tool-level sentinel that clears the override back to
--     NULL).
--
-- Both columns are additive, so existing rows are valid immediately: the defaults make every
-- pre-existing preset `compaction_enabled = 1` with no override, which reproduces the
-- preference-only behaviour exactly. No data update step is required. Downgrade is not supported;
-- older code simply ignores the columns.

ALTER TABLE model_presets ADD COLUMN compaction_enabled BOOLEAN NOT NULL DEFAULT 1;
ALTER TABLE model_presets ADD COLUMN compaction_threshold_tokens BIGINT;
