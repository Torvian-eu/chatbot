-- Removes the operational type from llm_models: a model can serve multiple purposes
-- (Chat Completions, Responses API, embeddings, image generation, etc.) and the type is
-- decided by the attached model_settings profile, not by the model itself.
-- model_settings.type is intentionally kept.
ALTER TABLE llm_models DROP COLUMN type;
