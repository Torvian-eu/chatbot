-- Strips the deprecated `systemMessage` (CHAT) and `instructions` (RESPONSES) JSON keys from
-- the `variable_params_json` column of `model_settings`.
--
-- Context: the `systemMessage`/`instructions` fields were removed from ChatModelSettings and
-- ResponsesModelSettings because the system prompt is now authored exclusively as agent-role
-- instructions (e.g. MODEL_SPECIFIC or CUSTOM). The values were stored inside the JSON
-- `variable_params_json` TEXT column rather than as separate columns, so no schema change is
-- needed — only the orphaned JSON keys are stripped.
--
-- `json_remove` is idempotent: if the key is absent or the JSON is an empty object, the value is
-- returned unchanged. The `json_valid` guard protects against malformed rows that would otherwise
-- raise an error (e.g. a row with a NULL or non-JSON payload from a partial migration).

UPDATE model_settings
SET variable_params_json = json_remove(variable_params_json, '$.systemMessage')
WHERE type = 'CHAT'
  AND json_valid(variable_params_json);

UPDATE model_settings
SET variable_params_json = json_remove(variable_params_json, '$.instructions')
WHERE type = 'RESPONSES'
  AND json_valid(variable_params_json);
