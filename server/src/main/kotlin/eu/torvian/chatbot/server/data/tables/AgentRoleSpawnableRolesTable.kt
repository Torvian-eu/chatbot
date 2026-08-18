package eu.torvian.chatbot.server.data.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

/**
 * Self-referencing relation describing which roles may be spawned by another role.
 *
 * The source and target foreign keys cascade with role deletion, while the composite primary key
 * prevents duplicate grants. The relation is unordered; a role may grant spawn permission to any
 * role it owns, including itself.
 *
 * @property sourceRoleId Role making the spawn request.
 * @property targetRoleId Role allowed to be spawned.
 */
object AgentRoleSpawnableRolesTable : Table("agent_role_spawnable_roles") {
    val sourceRoleId = reference("source_role_id", AgentRoleTable, onDelete = ReferenceOption.CASCADE)
    val targetRoleId = reference("target_role_id", AgentRoleTable, onDelete = ReferenceOption.CASCADE)

    override val primaryKey = PrimaryKey(sourceRoleId, targetRoleId)

    init {
        index(isUnique = false, targetRoleId)
    }
}
