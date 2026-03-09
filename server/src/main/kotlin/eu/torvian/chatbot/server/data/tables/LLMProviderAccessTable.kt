package eu.torvian.chatbot.server.data.tables

import eu.torvian.chatbot.server.data.tables.LLMProviderAccessTable.accessMode
import eu.torvian.chatbot.server.data.tables.LLMProviderAccessTable.providerId
import eu.torvian.chatbot.server.data.tables.LLMProviderAccessTable.userGroupId
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

/**
 * Defines which user groups (including 'All Users') can access an LLM provider.
 * 
 * This table enables group-based sharing of LLM providers. When a provider
 * is linked to the special "All Users" group, it becomes publicly accessible
 * to all users in the system.
 * 
 * @property providerId Reference to the LLM provider being shared
 * @property userGroupId Reference to the user group that has access to the provider
 * @property accessMode String representation of the access mode (e.g. "read", "write")
 */
object LLMProviderAccessTable : Table("llm_provider_access") {
    val providerId = reference("provider_id", LLMProviderTable, onDelete = ReferenceOption.CASCADE)
    val userGroupId = reference("user_group_id", UserGroupsTable, onDelete = ReferenceOption.CASCADE)
    val accessMode = varchar("access_mode", 50)

    // Composite primary key to allow multiple access modes per group
    override val primaryKey = PrimaryKey(providerId, userGroupId, accessMode)

    // Index for efficient lookups by provider and group
    init {
        index(false, providerId, userGroupId)
    }
}
