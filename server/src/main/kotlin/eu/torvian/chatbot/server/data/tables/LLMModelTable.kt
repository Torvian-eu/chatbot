package eu.torvian.chatbot.server.data.tables

import eu.torvian.chatbot.common.models.LLMModel
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.ReferenceOption

/**
 * Exposed table definition for LLM model configurations.
 * Corresponds to the [LLMModel] DTO.
 *
 * @property name Unique identifier for the LLM model (e.g., "gpt-3.5-turbo", "gpt-4")
 * @property providerId Reference to the LLM provider that hosts this model (CASCADE on delete)
 * @property active Whether the model can still be actively used
 * @property displayName Optional display name for UI purposes
 */
object LLMModelTable : LongIdTable("llm_models") {
    val name = varchar("name", 255).uniqueIndex()
    val providerId = reference("provider_id", LLMProviderTable, onDelete = ReferenceOption.CASCADE)
    val active = bool("active").default(true)
    val displayName = varchar("display_name", 255).nullable()
}
