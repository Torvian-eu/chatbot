package eu.torvian.chatbot.server.data.entities

import kotlin.time.Instant

/**
 * Represents a row from the `model_presets` database table.
 *
 * The preset's owner lives in `model_preset_owners` and is resolved separately; the agent roles bound
 * to the preset are not stored here either — they reference the preset through
 * `agent_roles.model_preset_id`.
 *
 * @property id Unique identifier for the model preset.
 * @property name Unique (per owner user) machine-readable preset name.
 * @property displayName Optional human-friendly display name.
 * @property description Free-form description of the preset.
 * @property modelId Optional identifier of the bundled LLM model; null when the preset has no model or
 *            the model was deleted (`SET NULL`).
 * @property modelSettingsId Optional identifier of the bundled settings profile; null when the preset
 *            has no settings profile or the profile was deleted (`SET NULL`).
 * @property createdAt Timestamp when the preset was created.
 * @property updatedAt Timestamp when the preset was last updated.
 */
data class ModelPresetEntity(
    val id: Long,
    val name: String,
    val displayName: String?,
    val description: String,
    val modelId: Long?,
    val modelSettingsId: Long?,
    val createdAt: Instant,
    val updatedAt: Instant
)
