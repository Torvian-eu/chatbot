-- Removes the unique constraint on llm_models.name so the same model name can be
-- reused across different model types (e.g. a CHAT and a RESPONSES variant of the same model).
DROP INDEX IF EXISTS llm_models_name_unique;
