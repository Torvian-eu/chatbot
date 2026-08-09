-- Store reasoning items emitted by Responses-capable models alongside the assistant message.
--
-- `reasoning_items_json` holds a JSON array of the raw Responses output items of type "reasoning"
-- (e.g. {"type":"reasoning","id":...,"summary":...,"encrypted_content":...,"status":...}) produced for
-- the assistant message. This enables the stateless replay of reasoning context into a future request's
-- `input` across turns. The payload is opaque (it may contain OpenAI-encrypted reasoning content) and
-- must never be logged or rendered. It is nullable: assistant messages from non-reasoning models carry NULL.

ALTER TABLE assistant_messages ADD COLUMN reasoning_items_json TEXT;
