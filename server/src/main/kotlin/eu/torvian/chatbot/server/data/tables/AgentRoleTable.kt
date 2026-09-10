package eu.torvian.chatbot.server.data.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

/**
 * Exposed table definition for user-defined agent roles.
 *
 * The attached tool set lives in the normalized `agent_role_tools` join table ([AgentRoleToolsTable]);
 * only the flat `instructions_json` document is stored as a JSON string in this table.
 *
 * The role's LLM configuration is NOT stored here: the role references a user-owned model preset
 * through the single `model_preset_id` column, and the preset is the sole source of truth for the
 * model and settings profile. The former direct `model_id`/`model_settings_id` columns were removed by
 * the V29 rebuild (SQLite cannot drop FK-constrained/indexed columns in place).
 *
 * @property name Machine-readable role name. Unique per user, enforced at the service layer — the
 *            column itself is deliberately NOT globally unique so different users may reuse the same
 *            name.
 * @property displayName Optional human-friendly display name.
 * @property description Free-form description of the role.
 * @property modelPresetId Optional reference to the model preset holding the role's LLM configuration
 *            (`SET NULL` on delete, so deleting a preset keeps the role and merely detaches its
 *            configuration). The preset (see [ModelPresetTable]) is the **sole** source of truth for
 *            the role's model and settings: the former `model_id`/`model_settings_id` columns were
 *            removed in V29 and no dormant fallback pair exists. Null means the role is
 *            **preset-less** and therefore non-sendable.
 * @property projectId Optional reference to the single project the role belongs to (`SET NULL` on
 *            delete). Null means the role is **unassociated** (offered only for project-less
 *            sessions). Membership was reduced from a set to a single column so same-project
 *            rules (spawn allow-list targets, name-uniqueness scope, Session Legality Invariant)
 *            are exact comparisons.
 * @property instructionsJson JSON array of the flat [eu.torvian.chatbot.common.models.agent.AgentInstructionDto]
 *            list (the same encoding used on the wire).
 * @property createdAt Timestamp when the role was created.
 * @property updatedAt Timestamp when the role was last updated.
 */
object AgentRoleTable : LongIdTable("agent_roles") {
    val name = varchar("name", 255)
    val displayName = varchar("display_name", 255).nullable()
    val description = text("description").default("")
    val modelPresetId = reference("model_preset_id", ModelPresetTable, onDelete = ReferenceOption.SET_NULL).nullable()
    val projectId = reference("project_id", ProjectTable, onDelete = ReferenceOption.SET_NULL).nullable()
    val instructionsJson = text("instructions_json").default("[]")
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")

    init {
        // Non-unique: name uniqueness is scoped per user and enforced by AgentRoleServiceImpl (the
        // DB cannot express a per-user unique constraint because ownership lives in a separate table).
        index(isUnique = false, name)
        index(isUnique = false, modelPresetId)
        index(isUnique = false, projectId)
    }
}
