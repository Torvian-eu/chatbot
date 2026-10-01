-- Store the provider-reported token usage of an assistant message.
--
-- `usage_stats` holds the JSON object of the shared UsageStats shape,
-- {"inputTokens":N,"outputTokens":N,"totalTokens":N,"reasoningTokens":N?,"cachedTokens":N?,"cacheWriteTokens":N?},
-- as reported by the provider for the generation that produced the message. It is nullable: a generation whose
-- provider reported no usage, a generation that did not complete, a row written before this column existed and a
-- manually inserted/cloned or edited message all read as "no usage". The counters are provider-reported integers;
-- an optional counter the provider omitted stays absent instead of being recorded as zero. No data migration is
-- required.

ALTER TABLE assistant_messages ADD COLUMN usage_stats TEXT;
