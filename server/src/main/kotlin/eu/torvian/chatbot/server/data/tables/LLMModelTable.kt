package eu.torvian.chatbot.server.data.tables

import eu.torvian.chatbot.common.models.llm.LLMModel
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

/**
 * Exposed table definition for LLM model configurations.
 * Corresponds to the [LLMModel] DTO.
 *
 * @property name The model's name (e.g., "gpt-3.5-turbo", "gpt-4"). Not necessarily unique — the same name
 *            may be reused across settings profiles of different types.
 * @property providerId Reference to the LLM provider that hosts this model (CASCADE on delete)
 * @property active Whether the model can still be actively used
 * @property displayName Optional display name for UI purposes
 * @property capabilities JSON object containing model capabilities (nullable)
 */
object LLMModelTable : LongIdTable("llm_models") {
    val name = varchar("name", 255)
    val providerId = reference("provider_id", LLMProviderTable, onDelete = ReferenceOption.CASCADE)
    val active = bool("active").default(true)
    val displayName = varchar("display_name", 255).nullable()
    val capabilities = text("capabilities").nullable()
}
