package eu.torvian.chatbot.server.data.tables

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table

/**
 * Links an LLM provider to its owning user.
 * 
 * This table establishes a one-to-one relationship between LLM providers
 * and their owners. Each provider has exactly one owner, and ownership
 * determines who can access, modify, or delete the provider configuration.
 * 
 * @property providerId Reference to the LLM provider being owned
 * @property userId Reference to the user who owns the provider
 */
object LLMProviderOwnersTable : Table("llm_provider_owners") {
    val providerId = reference("provider_id", LLMProviderTable, onDelete = ReferenceOption.CASCADE)
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE)

    // provider_id is primary key, ensuring 1 owner per provider
    override val primaryKey = PrimaryKey(providerId)
}
