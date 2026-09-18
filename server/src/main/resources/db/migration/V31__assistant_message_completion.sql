-- Record why an assistant message did not complete.
--
-- is_complete is the completion flag requested for the message: it defaults to TRUE so every pre-existing
-- assistant row stays completed (historical interruptions remain indistinguishable, exactly as before this
-- migration) and so manually inserted/cloned-completed rows need no extra value.
-- incomplete_cause is NULL while no terminal cause is known (an in-flight streaming placeholder, or a row
-- abandoned by a crash) and otherwise holds the enum name INTERRUPTED_BY_USER or FAILED. error_code and
-- error_message are only populated for FAILED: the code is a machine-readable enum name and the message is a
-- short, bounded, provider-internal-free text (raw provider bodies and exception dumps stay in server logs;
-- these columns are visible to every user with session access).

ALTER TABLE assistant_messages ADD COLUMN is_complete BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE assistant_messages ADD COLUMN incomplete_cause VARCHAR(50);
ALTER TABLE assistant_messages ADD COLUMN error_code VARCHAR(50);
ALTER TABLE assistant_messages ADD COLUMN error_message TEXT;
