package eu.torvian.chatbot.server.data.tables

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table

/**
 * Defines which user groups (including 'All Users') can access an LLM provider.
 * 
 * This table enables group-based sharing of LLM providers. When a provider
 * is linked to the special "All Users" group, it becomes publicly accessible
 * to all users in the system.
 * 
 * @property providerId Reference to the LLM provider being shared
 * @property userGroupId Reference to the user group that has access to the provider
 */
object LLMProviderAccessTable : Table("llm_provider_access") {
    val providerId = reference("provider_id", LLMProviderTable, onDelete = ReferenceOption.CASCADE)
    val userGroupId = reference("user_group_id", UserGroupsTable, onDelete = ReferenceOption.CASCADE)

    // Composite primary key to prevent duplicate access grants
    override val primaryKey = PrimaryKey(providerId, userGroupId)
}
