-- Store how long the LLM provider call that produced an assistant message took.
--
-- `response_duration_ms` is the wall-clock duration in milliseconds of the single provider call of the step that
-- produced this message: from request dispatch to the terminal signal of the response, including the streamed
-- reasoning output and the tool-call requests the model emitted, and excluding every tool execution. It is
-- nullable: a row written before this column existed, a message inserted or edited by hand, and a row whose
-- generation was never measured all read as "no duration". No data migration is required.

ALTER TABLE assistant_messages ADD COLUMN response_duration_ms BIGINT;
