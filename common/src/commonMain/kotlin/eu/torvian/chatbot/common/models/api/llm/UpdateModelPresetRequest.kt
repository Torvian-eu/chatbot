package eu.torvian.chatbot.common.models.api.llm

import kotlinx.serialization.Serializable

/**
 * Request body for updating an existing user-owned model preset.
 *
 * A **full replacement** of all five writable fields (mirroring
 * [eu.torvian.chatbot.common.models.api.project.UpdateProjectRequest] and the agent-role update):
 * fields the caller does not want to change must be sent with their current values, and a `null`
 * reference **clears** it. `createdAt`/`updatedAt` are deliberately absent — the server owns the
 * timestamps, so a client can neither forge nor clear them.
 *
 * @property name The new preset name, non-blank and at most
 *            [eu.torvian.chatbot.common.models.llm.MAX_MODEL_PRESET_NAME_LENGTH] characters. Must stay
 *            unique among the owner's presets (the preset being updated is excluded from the check).
 * @property displayName The new optional human-friendly display name; `null` clears it.
 * @property description The new free-form description of the preset.
 * @property modelId The new model reference, or `null` to clear it. The model must be accessible to
 *            the requesting user, and (when [modelSettingsId] is also present) must match the
 *            settings profile's model.
 * @property modelSettingsId The new settings-profile reference, or `null` to clear it. The profile
 *            must be accessible to the requesting user and, when [modelId] is also present, must
 *            belong to that model.
 */
@Serializable
data class UpdateModelPresetRequest(
    val name: String,
    val displayName: String? = null,
    val description: String = "",
    val modelId: Long? = null,
    val modelSettingsId: Long? = null
)
