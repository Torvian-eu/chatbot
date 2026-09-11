package eu.torvian.chatbot.server.data.entities

import kotlin.time.Instant

/**
 * Represents a row from the `agent_roles` database table.
 *
 * The complex `instructions` value is kept as its raw JSON string so serialization stays at the
 * mapper/service boundary: the entity is a plain projection of the table row, and
 * [instructionsJson] is parsed into typed domain values by the service layer. The role's tool ids are
 * NOT stored in this table — they live in the `agent_role_tools` join table and are loaded separately.
 *
 * The role's LLM configuration is stored **only** as [modelPresetId]: the model preset is the sole
 * source of truth, and the values the wire DTO exposes as `modelId`/`modelSettingsId` are derived
 * from that preset at the service layer. They are deliberately absent from this entity so no caller
 * can read or write them (the columns no longer exist).
 *
 * @property id Unique identifier for the agent role.
 * @property name Unique (per user) machine-readable role name.
 * @property displayName Optional human-friendly display name.
 * @property description Free-form description of the role.
 * @property modelPresetId Optional identifier of the model preset holding the role's model and
 *            settings profile; null means the role is **preset-less** and non-sendable, and is also
 *            the value `ON DELETE SET NULL` produces when the preset is deleted.
 * @property projectId Optional identifier of the single project the role belongs to; null means
 *            **unassociated**. Set via the membership column on create/update (a role belongs to at
 *            most one project) and nulled by `ON DELETE SET NULL` when the project is deleted.
 * @property instructionsJson Raw JSON array of the flat `AgentInstructionDto` list.
 * @property createdAt Timestamp when the role was created.
 * @property updatedAt Timestamp when the role was last updated.
 */
data class AgentRoleEntity(
    val id: Long,
    val name: String,
    val displayName: String?,
    val description: String,
    val modelPresetId: Long?,
    val instructionsJson: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val projectId: Long? = null
)
