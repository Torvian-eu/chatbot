package eu.torvian.chatbot.server.data.tables

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table

/**
 * Defines which user groups (including 'All Users') can access an LLM model.
 * 
 * This table enables group-based sharing of LLM models. When a model
 * is linked to the special "All Users" group, it becomes publicly accessible
 * to all users in the system.
 * 
 * @property modelId Reference to the LLM model being shared
 * @property userGroupId Reference to the user group that has access to the model
 * @property accessMode String representation of the access mode (e.g. "read", "write")
 */
object LLMModelAccessTable : Table("llm_model_access") {
    val modelId = reference("model_id", LLMModelTable, onDelete = ReferenceOption.CASCADE)
    val userGroupId = reference("user_group_id", UserGroupsTable, onDelete = ReferenceOption.CASCADE)
    val accessMode = varchar("access_mode", 50)

    // Composite primary key to allow multiple access modes per group
    override val primaryKey = PrimaryKey(modelId, userGroupId, accessMode)

    // Index for efficient lookups by model and group
    init {
        index(false, modelId, userGroupId)
    }
}
