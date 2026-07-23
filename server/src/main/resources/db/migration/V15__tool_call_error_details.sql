-- Extend tool_calls with machine-readable error metadata.
-- `error_code` mirrors the code reported by the executor (e.g. a worker-side authorization failure),
-- while `error_details` carries optional structured diagnostics as JSON text. Both are nullable and
-- only populated when a tool call fails, keeping the success/denial paths unchanged.

ALTER TABLE tool_calls ADD COLUMN error_code VARCHAR(255);
ALTER TABLE tool_calls ADD COLUMN error_details TEXT;
