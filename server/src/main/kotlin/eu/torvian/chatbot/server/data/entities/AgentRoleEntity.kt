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
 * @property id Unique identifier for the agent role.
 * @property name Unique (per user) machine-readable role name.
 * @property displayName Optional human-friendly display name.
 * @property description Free-form description of the role.
 * @property modelId Optional identifier of the LLM model used by the role; null after the model is
 *            deleted (`SET NULL`).
 * @property modelSettingsId Optional identifier of the settings profile used by the role; null after
 *            the settings are deleted (`SET NULL`).
 * @property instructionsJson Raw JSON array of the flat `AgentInstructionDto` list.
 * @property createdAt Timestamp when the role was created.
 * @property updatedAt Timestamp when the role was last updated.
 */
data class AgentRoleEntity(
    val id: Long,
    val name: String,
    val displayName: String?,
    val description: String,
    val modelId: Long?,
    val modelSettingsId: Long?,
    val instructionsJson: String,
    val createdAt: Instant,
    val updatedAt: Instant
)
