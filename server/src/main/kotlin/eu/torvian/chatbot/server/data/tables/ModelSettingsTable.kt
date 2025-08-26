package eu.torvian.chatbot.server.data.tables

import eu.torvian.chatbot.common.models.LLMModelType
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.ReferenceOption

/**
 * Exposed table definition for LLM model settings profiles.
 * This table uses a single JSONB (or TEXT) column to store variable parameters
 * for different types of LLM models, based on the [type] discriminator.
 *
 * @property modelId Reference to the parent LLM model.
 * @property name The name of the settings profile (e.g., "Default", "Creative", "Strict").
 * @property type The [LLMModelType] that this settings profile applies to (e.g., CHAT, EMBEDDING).
 * @property variableParamsJson A JSON string containing parameters specific to the [type] of LLM model.
 *                              This column is NOT NULL, so an empty JSON object "{}" should be stored
 *                              if there are no type-specific parameters.
 * @property customParamsJson Arbitrary, truly custom model-specific parameters stored as a JSON string.
 */
object ModelSettingsTable : LongIdTable("model_settings") {
    val modelId = reference("model_id", LLMModelTable, onDelete = ReferenceOption.CASCADE)
    val name = varchar("name", 255)
    val type = enumerationByName("type", 50, LLMModelType::class)
    val variableParamsJson = text("variable_params_json")
    val customParamsJson = text("custom_params_json").nullable()

    // Add index for modelId to speed up settings lookup by model
    init {
        index(false, modelId)
    }
}
