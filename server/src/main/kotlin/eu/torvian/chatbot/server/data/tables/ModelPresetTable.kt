package eu.torvian.chatbot.server.data.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

/**
 * Exposed table definition for user-owned model presets.
 *
 * A model preset bundles one referenced LLM model and one referenced settings profile; it is the sole
 * source of truth for the LLM configuration of every agent role bound to it (the role row carries only
 * `agent_roles.model_preset_id`). Ownership lives in `model_preset_owners` ([ModelPresetOwnersTable]) —
 * a preset has exactly one owner and is neither project-scoped nor shareable.
 *
 * @property name Machine-readable preset name. Unique per owner user, enforced at the service layer
 *            (`ModelPresetServiceImpl`) — the column itself is deliberately NOT globally unique so
 *            different users may reuse the same name, mirroring `agent_roles.name`/`projects.name`.
 * @property displayName Optional human-friendly display name.
 * @property description Free-form description of the preset.
 * @property modelId Optional reference to the bundled LLM model (`SET NULL` on delete, so deleting a
 *            model keeps the preset row and simply strips one of its references).
 * @property modelSettingsId Optional reference to the bundled settings profile (`SET NULL` on delete).
 *            The referenced profile may be of any [eu.torvian.chatbot.common.models.llm.LLMModelType];
 *            chat capability is enforced where a preset drives an agent-role turn, not here.
 * @property createdAt Timestamp when the preset was created.
 * @property updatedAt Timestamp when the preset was last updated.
 */
object ModelPresetTable : LongIdTable("model_presets") {
    val name = varchar("name", 255)
    val displayName = varchar("display_name", 255).nullable()
    val description = text("description").default("")
    val modelId = reference("model_id", LLMModelTable, onDelete = ReferenceOption.SET_NULL).nullable()
    val modelSettingsId =
        reference("model_settings_id", ModelSettingsTable, onDelete = ReferenceOption.SET_NULL).nullable()
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")

    init {
        // Non-unique: name uniqueness is scoped per owner user and enforced by ModelPresetServiceImpl
        // (the DB cannot express a per-user unique constraint because ownership lives in a separate
        // table, mirroring agent_roles/projects).
        index(isUnique = false, name)
        index(isUnique = false, modelId)
        index(isUnique = false, modelSettingsId)
    }
}
