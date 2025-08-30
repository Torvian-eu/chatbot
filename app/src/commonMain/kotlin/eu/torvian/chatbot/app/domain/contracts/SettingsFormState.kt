package eu.torvian.chatbot.app.domain.contracts

import eu.torvian.chatbot.common.models.ChatModelSettings
import eu.torvian.chatbot.common.models.EmbeddingModelSettings
import eu.torvian.chatbot.common.models.LLMModelType
import eu.torvian.chatbot.common.models.ModelSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Enum representing the mode of the form - whether creating new settings or editing existing ones.
 */
enum class FormMode {
    NEW,
    EDIT
}

/**
 * Sealed class representing the state of form inputs for different types of ModelSettings.
 * Uses FormMode to distinguish between creating new settings and editing existing ones.
 */
sealed class SettingsFormState {
    abstract val mode: FormMode
    abstract val name: String
    abstract val customParamsJson: String
    abstract val errorMessage: String?
    abstract val modelType: LLMModelType

    data class Chat(
        override val mode: FormMode = FormMode.NEW,
        override val name: String = "",
        val systemMessage: String = "",
        val temperature: String = "",
        val maxTokens: String = "",
        val topP: String = "",
        val topK: String = "",
        val stopSequences: String = "",
        val stream: Boolean = true,
        override val customParamsJson: String = "",
        override val errorMessage: String? = null
    ) : SettingsFormState() {
        override val modelType: LLMModelType = LLMModelType.CHAT
    }

    data class Embedding(
        override val mode: FormMode = FormMode.NEW,
        override val name: String = "",
        val dimensions: String = "",
        val encodingFormat: String = "",
        override val customParamsJson: String = "",
        override val errorMessage: String? = null
    ) : SettingsFormState() {
        override val modelType: LLMModelType = LLMModelType.EMBEDDING
    }
}

/**
 * Creates a form state from an existing ModelSettings instance for editing.
 */
fun ModelSettings.toEditFormState(): SettingsFormState {
    return when (this) {
        is ChatModelSettings -> SettingsFormState.Chat(
            mode = FormMode.EDIT,
            name = this.name,
            systemMessage = this.systemMessage ?: "",
            temperature = this.temperature?.toString() ?: "",
            maxTokens = this.maxTokens?.toString() ?: "",
            topP = this.topP?.toString() ?: "",
            topK = this.topK?.toString() ?: "",
            stopSequences = this.stopSequences?.joinToString(",") ?: "",
            stream = this.stream,
            customParamsJson = this.customParams?.let { Json.encodeToString(JsonObject.serializer(), it) } ?: ""
        )

        is EmbeddingModelSettings -> SettingsFormState.Embedding(
            mode = FormMode.EDIT,
            name = this.name,
            dimensions = this.dimensions?.toString() ?: "",
            encodingFormat = this.encodingFormat ?: "",
            customParamsJson = this.customParams?.let { Json.encodeToString(JsonObject.serializer(), it) } ?: ""
        )

        else -> throw IllegalArgumentException("Unsupported ModelSettings type: ${this::class.simpleName}")
    }
}

/**
 * Creates a ModelSettings instance from a form state.
 */
fun SettingsFormState.toModelSettings(
    id: Long,
    modelId: Long
): ModelSettings {
    val customParams = this.customParamsJson.trim().takeIf { it.isNotBlank() }?.let { jsonString ->
        Json.decodeFromString<JsonObject>(jsonString)
    }

    return when (this) {
        is SettingsFormState.Chat -> {
            val stopSequencesList = this.stopSequences.split(',')
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .takeIf { it.isNotEmpty() }

            ChatModelSettings(
                id = id,
                modelId = modelId,
                name = this.name.trim(),
                systemMessage = this.systemMessage.trim().takeIf { it.isNotBlank() },
                temperature = this.temperature.toFloatOrNull(),
                maxTokens = this.maxTokens.toIntOrNull(),
                topP = this.topP.toFloatOrNull(),
                topK = this.topK.toIntOrNull(),
                stopSequences = stopSequencesList,
                stream = this.stream,
                customParams = customParams
            )
        }

        is SettingsFormState.Embedding -> {
            EmbeddingModelSettings(
                id = id,
                modelId = modelId,
                name = this.name.trim(),
                dimensions = this.dimensions.toIntOrNull(),
                encodingFormat = this.encodingFormat.trim().takeIf { it.isNotBlank() },
                customParams = customParams
            )
        }
    }
}

/**
 * Validates a form state and returns validation errors if any.
 */
fun SettingsFormState.validate(): String? {
    if (this.name.isBlank()) {
        return "Settings profile name cannot be empty."
    }

    when (this) {
        is SettingsFormState.Chat -> {
            if (this.temperature.isNotBlank() && this.temperature.toFloatOrNull() == null) {
                return "Temperature must be a number."
            }
            if (this.maxTokens.isNotBlank() && this.maxTokens.toIntOrNull() == null) {
                return "Max Tokens must be an integer."
            }
            if (this.topP.isNotBlank() && this.topP.toFloatOrNull() == null) {
                return "Top P must be a number."
            }
            if (this.topK.isNotBlank() && this.topK.toIntOrNull() == null) {
                return "Top K must be an integer."
            }
        }

        is SettingsFormState.Embedding -> {
            if (this.dimensions.isNotBlank() && this.dimensions.toIntOrNull() == null) {
                return "Dimensions must be an integer."
            }
        }
    }

    if (this.customParamsJson.isNotBlank()) {
        try {
            Json.decodeFromString<JsonObject>(this.customParamsJson)
        } catch (_: Exception) {
            return "Invalid JSON format in custom parameters."
        }
    }

    return null
}

/**
 * Updates a form state with an error message, preserving all other data.
 */
fun SettingsFormState.withError(errorMessage: String?): SettingsFormState {
    return when (this) {
        is SettingsFormState.Chat -> this.copy(errorMessage = errorMessage)
        is SettingsFormState.Embedding -> this.copy(errorMessage = errorMessage)
    }
}

/**
 * Creates an empty form state for a new settings profile of the specified type.
 */
fun createEmptyNewSettingsForm(modelType: LLMModelType): SettingsFormState {
    return when (modelType) {
        LLMModelType.CHAT -> SettingsFormState.Chat(mode = FormMode.NEW)
        LLMModelType.EMBEDDING -> SettingsFormState.Embedding(mode = FormMode.NEW)
        else -> throw IllegalArgumentException("Unsupported model type for settings form: $modelType")
    }
}

/**
 * Returns the list of supported model types that have form state implementations.
 */
fun getSupportedSettingsTypes(): List<LLMModelType> {
    return listOf(LLMModelType.CHAT, LLMModelType.EMBEDDING)
}

/**
 * Checks if a ModelSettings instance is of a supported type that can be edited.
 */
fun isModelSettingsSupported(settings: ModelSettings): Boolean {
    return when (settings) {
        is ChatModelSettings, is EmbeddingModelSettings -> true
        else -> false
    }
}
