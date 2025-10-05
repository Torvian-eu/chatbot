package eu.torvian.chatbot.server.data.tables

import eu.torvian.chatbot.common.models.llm.LLMProvider
import eu.torvian.chatbot.common.models.llm.LLMProviderType
import org.jetbrains.exposed.dao.id.LongIdTable

/**
 * Exposed table definition for LLM provider configurations.
 * Corresponds to the [LLMProvider] DTO.
 *
 * This table stores LLM provider configurations including their API key references,
 * endpoints, and metadata. Each provider represents a configured LLM service endpoint.
 *
 * @property apiKeyId Reference to the credential storage system for the API key (unique, nullable for local providers)
 * @property name The display name for the provider
 * @property description Optional description providing context about the provider
 * @property baseUrl The base URL for the LLM API endpoint
 * @property type The type of LLM provider
 */
object LLMProviderTable : LongIdTable("llm_providers") {
    val apiKeyId = varchar("api_key_id", 255).nullable().uniqueIndex()
    val name = varchar("name", 255)
    val description = text("description")
    val baseUrl = varchar("base_url", 500)
    val type = enumerationByName<LLMProviderType>("type", 50)
}
