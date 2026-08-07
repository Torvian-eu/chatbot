package eu.torvian.chatbot.app.domain.contracts

import eu.torvian.chatbot.common.models.llm.ChatModelSettings
import eu.torvian.chatbot.common.models.llm.EmbeddingModelSettings
import eu.torvian.chatbot.common.models.llm.LLMModelType
import eu.torvian.chatbot.common.models.llm.ModelSettings
import eu.torvian.chatbot.common.models.llm.ResponsesModelSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Sealed class representing the state of form inputs for different types of ModelSettings.
 * Uses FormMode to distinguish between creating new settings and editing existing ones.
 */
sealed class ModelSettingsFormState {
    abstract val mode: FormMode
    abstract val name: String
    abstract val modelId: Long?
    abstract val customParamsJson: String
    abstract val errorMessage: String?
    abstract val modelType: LLMModelType

    data class Chat(
        override val mode: FormMode = FormMode.NEW,
        override val name: String = "",
        override val modelId: Long? = null,
        val systemMessage: String = "",
        val temperature: String = "",
        val maxTokens: String = "",
        val topP: String = "",
        val stopSequences: String = "",
        val stream: Boolean = true,
        override val customParamsJson: String = "",
        override val errorMessage: String? = null
    ) : ModelSettingsFormState() {
        override val modelType: LLMModelType = LLMModelType.CHAT
    }

    data class Embedding(
        override val mode: FormMode = FormMode.NEW,
        override val name: String = "",
        override val modelId: Long? = null,
        val dimensions: String = "",
        val encodingFormat: String = "",
        override val customParamsJson: String = "",
        override val errorMessage: String? = null
    ) : ModelSettingsFormState() {
        override val modelType: LLMModelType = LLMModelType.EMBEDDING
    }

    data class Responses(
        override val mode: FormMode = FormMode.NEW,
        override val name: String = "",
        override val modelId: Long? = null,
        val instructions: String = "",
        val temperature: String = "",
        val maxOutputTokens: String = "",
        val topP: String = "",
        val stopSequences: String = "",
        val stream: Boolean = true,
        val reasoningEffort: String = "",
        val store: Boolean = false,
        val replayReasoning: Boolean = false,
        override val customParamsJson: String = "",
        override val errorMessage: String? = null
    ) : ModelSettingsFormState() {
        override val modelType: LLMModelType = LLMModelType.RESPONSES
    }
}

/**
 * Creates a form state from an existing ModelSettings instance for editing.
 */
fun ModelSettings.toEditFormState(): ModelSettingsFormState {
    return when (this) {
        is ChatModelSettings -> ModelSettingsFormState.Chat(
            mode = FormMode.EDIT,
            name = this.name,
            modelId = this.modelId,
            systemMessage = this.systemMessage ?: "",
            temperature = this.temperature?.toString() ?: "",
            maxTokens = this.maxTokens?.toString() ?: "",
            topP = this.topP?.toString() ?: "",
            stopSequences = this.stopSequences?.joinToString(",") ?: "",
            stream = this.stream,
            customParamsJson = this.customParams?.let { Json.encodeToString(JsonObject.serializer(), it) } ?: ""
        )

        is EmbeddingModelSettings -> ModelSettingsFormState.Embedding(
            mode = FormMode.EDIT,
            name = this.name,
            modelId = this.modelId,
            dimensions = this.dimensions?.toString() ?: "",
            encodingFormat = this.encodingFormat ?: "",
            customParamsJson = this.customParams?.let { Json.encodeToString(JsonObject.serializer(), it) } ?: ""
        )

        is ResponsesModelSettings -> ModelSettingsFormState.Responses(
            mode = FormMode.EDIT,
            name = this.name,
            modelId = this.modelId,
            instructions = this.instructions ?: "",
            temperature = this.temperature?.toString() ?: "",
            maxOutputTokens = this.maxOutputTokens?.toString() ?: "",
            topP = this.topP?.toString() ?: "",
            stopSequences = this.stopSequences?.joinToString(",") ?: "",
            stream = this.stream,
            reasoningEffort = this.reasoningEffort ?: "",
            store = this.store,
            replayReasoning = this.replayReasoning,
            customParamsJson = this.customParams?.let { Json.encodeToString(JsonObject.serializer(), it) } ?: ""
        )

        else -> throw IllegalArgumentException("Unsupported ModelSettings type: ${this::class.simpleName}")
    }
}

/**
 * Creates a ModelSettings instance from a form state.
 */
fun ModelSettingsFormState.toModelSettings(
    id: Long,
    modelId: Long
): ModelSettings {
    val customParams = this.customParamsJson.trim().takeIf { it.isNotBlank() }?.let { jsonString ->
        Json.decodeFromString<JsonObject>(jsonString)
    }

    return when (this) {
        is ModelSettingsFormState.Chat -> {
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
                stopSequences = stopSequencesList,
                stream = this.stream,
                customParams = customParams
            )
        }

        is ModelSettingsFormState.Embedding -> {
            EmbeddingModelSettings(
                id = id,
                modelId = modelId,
                name = this.name.trim(),
                dimensions = this.dimensions.toIntOrNull(),
                encodingFormat = this.encodingFormat.trim().takeIf { it.isNotBlank() },
                customParams = customParams
            )
        }

        is ModelSettingsFormState.Responses -> {
            val stopSequencesList = this.stopSequences.split(',')
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .takeIf { it.isNotEmpty() }

            ResponsesModelSettings(
                id = id,
                modelId = modelId,
                name = this.name.trim(),
                instructions = this.instructions.trim().takeIf { it.isNotBlank() },
                temperature = this.temperature.toFloatOrNull(),
                maxOutputTokens = this.maxOutputTokens.toIntOrNull(),
                topP = this.topP.toFloatOrNull(),
                stopSequences = stopSequencesList,
                stream = this.stream,
                reasoningEffort = this.reasoningEffort.trim().takeIf { it.isNotBlank() },
                store = this.store,
                replayReasoning = this.replayReasoning,
                customParams = customParams
            )
        }
    }
}

/**
 * Validates a form state and returns validation errors if any.
 */
fun ModelSettingsFormState.validate(): String? {
    if (this.name.isBlank()) {
        return "Settings profile name cannot be empty."
    }
    if (this.modelId == null) {
        return "Model must be selected."
    }
    if (this.customParamsJson.isNotBlank()) {
        try {
            Json.decodeFromString<JsonObject>(this.customParamsJson)
        } catch (_: Exception) {
            return "Invalid JSON format in custom parameters."
        }
    }

    when (this) {
        is ModelSettingsFormState.Chat -> {
            if (this.temperature.isNotBlank() && this.temperature.toFloatOrNull() == null) {
                return "Temperature must be a number."
            }
            if (this.maxTokens.isNotBlank() && this.maxTokens.toIntOrNull() == null) {
                return "Max Tokens must be an integer."
            }
            if (this.topP.isNotBlank() && this.topP.toFloatOrNull() == null) {
                return "Top P must be a number."
            }
        }

        is ModelSettingsFormState.Embedding -> {
            if (this.dimensions.isNotBlank() && this.dimensions.toIntOrNull() == null) {
                return "Dimensions must be an integer."
            }
        }

        is ModelSettingsFormState.Responses -> {
            if (this.temperature.isNotBlank() && this.temperature.toFloatOrNull() == null) {
                return "Temperature must be a number."
            }
            if (this.maxOutputTokens.isNotBlank() && this.maxOutputTokens.toIntOrNull() == null) {
                return "Max Output Tokens must be an integer."
            }
            if (this.topP.isNotBlank() && this.topP.toFloatOrNull() == null) {
                return "Top P must be a number."
            }
        }
    }

    return null
}

/**
 * Updates a form state with an error message, preserving all other data.
 */
fun ModelSettingsFormState.withError(errorMessage: String?): ModelSettingsFormState {
    return when (this) {
        is ModelSettingsFormState.Chat -> this.copy(errorMessage = errorMessage)
        is ModelSettingsFormState.Embedding -> this.copy(errorMessage = errorMessage)
        is ModelSettingsFormState.Responses -> this.copy(errorMessage = errorMessage)
    }
}

/**
 * Creates an empty form state for a new settings profile of the specified type.
 */
fun createEmptyNewSettingsForm(modelType: LLMModelType, modelId: Long): ModelSettingsFormState {
    return when (modelType) {
        LLMModelType.CHAT -> ModelSettingsFormState.Chat(mode = FormMode.NEW, modelId = modelId)
        LLMModelType.EMBEDDING -> ModelSettingsFormState.Embedding(mode = FormMode.NEW, modelId = modelId)
        LLMModelType.RESPONSES -> ModelSettingsFormState.Responses(mode = FormMode.NEW, modelId = modelId)
        else -> throw IllegalArgumentException("Unsupported model type for settings form: $modelType")
    }
}

/**
 * Returns the list of supported model types that have form state implementations.
 */
fun getSupportedSettingsTypes(): List<LLMModelType> {
    return listOf(LLMModelType.CHAT, LLMModelType.EMBEDDING, LLMModelType.RESPONSES)
}

/**
 * Checks if a ModelSettings instance is of a supported type that can be edited.
 */
fun isModelSettingsSupported(settings: ModelSettings): Boolean {
    return getSupportedSettingsTypes().contains(settings.modelType)
}
