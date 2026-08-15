package eu.torvian.chatbot.server.data.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

/**
 * Exposed table definition for user-defined agent roles.
 *
 * The attached tool set lives in the normalized `agent_role_tools` join table ([AgentRoleToolsTable]);
 * only the flat `instructions_json` document is stored as a JSON string in this table.
 *
 * @property name Machine-readable role name. Unique per user, enforced at the service layer — the
 *            column itself is deliberately NOT globally unique so different users may reuse the same
 *            name.
 * @property displayName Optional human-friendly display name.
 * @property description Free-form description of the role.
 * @property modelId Optional reference to the LLM model used by the role (`SET NULL` on delete).
 * @property modelSettingsId Optional reference to the settings profile (CHAT/RESPONSES) used by the
 *            role (`SET NULL` on delete).
 * @property instructionsJson JSON array of the flat [eu.torvian.chatbot.common.models.agent.AgentInstructionDto]
 *            list (the same encoding used on the wire).
 * @property createdAt Timestamp when the role was created.
 * @property updatedAt Timestamp when the role was last updated.
 */
object AgentRoleTable : LongIdTable("agent_roles") {
    val name = varchar("name", 255)
    val displayName = varchar("display_name", 255).nullable()
    val description = text("description").default("")
    val modelId = reference("model_id", LLMModelTable, onDelete = ReferenceOption.SET_NULL).nullable()
    val modelSettingsId =
        reference("model_settings_id", ModelSettingsTable, onDelete = ReferenceOption.SET_NULL).nullable()
    val instructionsJson = text("instructions_json").default("[]")
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")

    init {
        // Non-unique: name uniqueness is scoped per user and enforced by AgentRoleServiceImpl (the
        // DB cannot express a per-user unique constraint because ownership lives in a separate table).
        index(isUnique = false, name)
        index(isUnique = false, modelSettingsId)
    }
}
