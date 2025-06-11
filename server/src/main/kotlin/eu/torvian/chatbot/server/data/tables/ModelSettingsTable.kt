package eu.torvian.chatbot.server.data.tables

import eu.torvian.chatbot.common.models.ModelSettings
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.ReferenceOption

/**
 * Exposed table definition for LLM model settings profiles.
 * Corresponds to the [ModelSettings] DTO.
 *
 * @property modelId Reference to the parent LLM model
 * @property name The name of the settings profile (e.g., "Default", "Creative", "Strict")
 * @property systemMessage The system message to use with this settings profile
 * @property temperature The temperature parameter for controlling randomness in generation
 * @property maxTokens The maximum number of tokens to generate
 * @property customParamsJson Arbitrary JSON for extra model-specific parameters
 */
object ModelSettingsTable : LongIdTable("model_settings") {
    val modelId = reference("model_id", LLMModelTable, onDelete = ReferenceOption.CASCADE)
    val name = varchar("name", 255)
    val systemMessage = text("system_message").nullable()
    val temperature = float("temperature").nullable()
    val maxTokens = integer("max_tokens").nullable()
    val customParamsJson = text("custom_params_json").nullable()

    // Add index for modelId to speed up settings lookup by model (E4.S5)
    init {
        index(false, modelId)
    }
}
