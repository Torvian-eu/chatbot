package eu.torvian.chatbot.server.data.tables

import eu.torvian.chatbot.common.models.LLMModel
import org.jetbrains.exposed.dao.id.LongIdTable

/**
 * Exposed table definition for LLM model configurations.
 * Corresponds to the [LLMModel] DTO.
 *
 * @property name The unique name of the LLM model
 * @property baseUrl The base URL for API requests to the model
 * @property apiKeyId Reference ID to the securely stored API key (not the key itself)
 * @property type The type of LLM model (e.g., "openai", "openrouter", "custom")
 */
object LLMModelTable : LongIdTable("llm_models") {
    val name = varchar("name", 255).uniqueIndex()
    val baseUrl = varchar("base_url", 512)
    val apiKeyId = varchar("api_key_id", 255).nullable().uniqueIndex()
    val type = varchar("type", 50)
}
