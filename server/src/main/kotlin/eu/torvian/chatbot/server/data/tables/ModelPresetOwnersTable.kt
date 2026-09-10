package eu.torvian.chatbot.server.data.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

/**
 * Exposed table definition for model-preset ownership links.
 *
 * Model presets are per-user: a preset has exactly one owner, mirroring the
 * `project_owners`/`agent_role_owners` family (`preset_id` is the primary key). Presets are
 * user-wide and not shareable, so there is no access-grant table and no new permission.
 *
 * @property presetId Reference to the owned preset (primary key, `CASCADE` on delete).
 * @property userId Reference to the owning user (`CASCADE` on delete).
 */
object ModelPresetOwnersTable : Table("model_preset_owners") {
    val presetId = reference("preset_id", ModelPresetTable, onDelete = ReferenceOption.CASCADE)
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE)

    override val primaryKey = PrimaryKey(presetId)
}
