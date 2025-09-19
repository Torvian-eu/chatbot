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
 */
object LLMModelAccessTable : Table("llm_model_access") {
    val modelId = reference("model_id", LLMModelTable, onDelete = ReferenceOption.CASCADE)
    val userGroupId = reference("user_group_id", UserGroupsTable, onDelete = ReferenceOption.CASCADE)

    // Composite primary key to prevent duplicate access grants
    override val primaryKey = PrimaryKey(modelId, userGroupId)
}
