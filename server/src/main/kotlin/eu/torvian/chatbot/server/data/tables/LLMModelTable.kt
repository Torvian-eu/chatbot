package eu.torvian.chatbot.server.data.tables

import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.LLMModelType
import eu.torvian.chatbot.server.data.tables.LLMModelTable.active
import eu.torvian.chatbot.server.data.tables.LLMModelTable.capabilities
import eu.torvian.chatbot.server.data.tables.LLMModelTable.displayName
import eu.torvian.chatbot.server.data.tables.LLMModelTable.name
import eu.torvian.chatbot.server.data.tables.LLMModelTable.providerId
import eu.torvian.chatbot.server.data.tables.LLMModelTable.type
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

/**
 * Exposed table definition for LLM model configurations.
 * Corresponds to the [LLMModel] DTO.
 *
 * @property name Unique identifier for the LLM model (e.g., "gpt-3.5-turbo", "gpt-4")
 * @property providerId Reference to the LLM provider that hosts this model (CASCADE on delete)
 * @property active Whether the model can still be actively used
 * @property displayName Optional display name for UI purposes
 * @property type The operational type of this model (e.g., CHAT, EMBEDDING, etc.)
 * @property capabilities JSON object containing model capabilities (nullable)
 */
object LLMModelTable : LongIdTable("llm_models") {
    val name = varchar("name", 255).uniqueIndex()
    val providerId = reference("provider_id", LLMProviderTable, onDelete = ReferenceOption.CASCADE)
    val active = bool("active").default(true)
    val displayName = varchar("display_name", 255).nullable()
    val type = enumerationByName<LLMModelType>("type", 50)
    val capabilities = text("capabilities").nullable()
}
