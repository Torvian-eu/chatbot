package eu.torvian.chatbot.server.data

import eu.torvian.chatbot.common.models.*
import eu.torvian.chatbot.server.data.tables.ModelSettingsTable
import kotlinx.serialization.json.*

/**
 * An internal DTO that mirrors the [ModelSettingsTable] structure,
 * used for mapping between the domain-level [ModelSettings] sealed hierarchy
 * and the flat database table representation.
 */
data class ModelSettingsEntity(
    val id: Long,
    val modelId: Long,
    val name: String,
    val type: LLMModelType,
    val variableParamsJson: String,
    val customParamsJson: String?
)

/**
 * Converts a domain-level [ModelSettings] instance into a database-friendly [ModelSettingsEntity].
 * This involves extracting common fields and serializing type-specific parameters into a JSON string.
 */
fun ModelSettings.toEntity(): ModelSettingsEntity {
    val variableParamsMap = buildJsonObject {
        when (this@toEntity) {
            is ChatModelSettings -> {
                systemMessage?.let { put("systemMessage", it) }
                temperature?.let { put("temperature", it) }
                maxTokens?.let { put("maxTokens", it) }
                topP?.let { put("topP", it) }
                topK?.let { put("topK", it) }
                stopSequences?.let { putJsonArray("stopSequences") { it.forEach(::add) } }
                put("stream", stream)
            }

            is CompletionModelSettings -> {
                suffix?.let { put("suffix", it) }
                temperature?.let { put("temperature", it) }
                maxTokens?.let { put("maxTokens", it) }
                topP?.let { put("topP", it) }
                topK?.let { put("topK", it) }
                stopSequences?.let { putJsonArray("stopSequences") { it.forEach(::add) } }
            }

            is EmbeddingModelSettings -> {
                dimensions?.let { put("dimensions", it) }
                encodingFormat?.let { put("encodingFormat", it) }
            }

            is ImageGenerationModelSettings -> {
                size?.let { put("size", it) }
                quality?.let { put("quality", it) }
                style?.let { put("style", it) }
                numImages?.let { put("numImages", it) }
            }

            is SpeechToTextModelSettings -> {
                language?.let { put("language", it) }
                responseFormat?.let { put("responseFormat", it) }
                prompt?.let { put("prompt", it) }
            }

            is TextToSpeechModelSettings -> {
                voice?.let { put("voice", it) }
                responseFormat?.let { put("responseFormat", it) }
                speed?.let { put("speed", it) }
            }

            is AqaModelSettings -> {
                maxSources?.let { put("maxSources", it) }
                answerabilityThreshold?.let { put("answerabilityThreshold", it) }
            }
        }
    }

    return ModelSettingsEntity(
        id = id,
        modelId = modelId,
        name = name,
        type = when (this) {
            is ChatModelSettings -> LLMModelType.CHAT
            is CompletionModelSettings -> LLMModelType.COMPLETION
            is EmbeddingModelSettings -> LLMModelType.EMBEDDING
            is ImageGenerationModelSettings -> LLMModelType.IMAGE_GENERATION
            is SpeechToTextModelSettings -> LLMModelType.SPEECH_TO_TEXT
            is TextToSpeechModelSettings -> LLMModelType.TEXT_TO_SPEECH
            is AqaModelSettings -> LLMModelType.AQA
        },
        variableParamsJson = Json.encodeToString(variableParamsMap),
        customParamsJson = customParams?.let { Json.encodeToString(it) }
    )
}

/**
 * Converts a database-level [ModelSettingsEntity] into a domain-level [ModelSettings] instance.
 * This involves parsing the JSON strings and instantiating the correct sealed class subtype.
 */
fun ModelSettingsEntity.toDomain(): ModelSettings {
    val parsedVariableParams = Json.parseToJsonElement(variableParamsJson).jsonObject
    val parsedCustomParams = customParamsJson?.let { Json.parseToJsonElement(it).jsonObject }

    return when (type) {
        LLMModelType.CHAT -> ChatModelSettings(
            id = id,
            modelId = modelId,
            name = name,
            systemMessage = parsedVariableParams["systemMessage"]?.jsonPrimitive?.contentOrNull,
            temperature = parsedVariableParams["temperature"]?.jsonPrimitive?.floatOrNull,
            maxTokens = parsedVariableParams["maxTokens"]?.jsonPrimitive?.intOrNull,
            topP = parsedVariableParams["topP"]?.jsonPrimitive?.floatOrNull,
            topK = parsedVariableParams["topK"]?.jsonPrimitive?.intOrNull,
            stopSequences = parsedVariableParams["stopSequences"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull },
            stream = parsedVariableParams["stream"]?.jsonPrimitive?.booleanOrNull ?: true,
            customParams = parsedCustomParams
        )

        LLMModelType.COMPLETION -> CompletionModelSettings(
            id = id,
            modelId = modelId,
            name = name,
            suffix = parsedVariableParams["suffix"]?.jsonPrimitive?.contentOrNull,
            temperature = parsedVariableParams["temperature"]?.jsonPrimitive?.floatOrNull,
            maxTokens = parsedVariableParams["maxTokens"]?.jsonPrimitive?.intOrNull,
            topP = parsedVariableParams["topP"]?.jsonPrimitive?.floatOrNull,
            topK = parsedVariableParams["topK"]?.jsonPrimitive?.intOrNull,
            stopSequences = parsedVariableParams["stopSequences"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull },
            customParams = parsedCustomParams
        )

        LLMModelType.EMBEDDING -> EmbeddingModelSettings(
            id = id,
            modelId = modelId,
            name = name,
            dimensions = parsedVariableParams["dimensions"]?.jsonPrimitive?.intOrNull,
            encodingFormat = parsedVariableParams["encodingFormat"]?.jsonPrimitive?.contentOrNull,
            customParams = parsedCustomParams
        )

        LLMModelType.IMAGE_GENERATION -> ImageGenerationModelSettings(
            id = id,
            modelId = modelId,
            name = name,
            size = parsedVariableParams["size"]?.jsonPrimitive?.contentOrNull,
            quality = parsedVariableParams["quality"]?.jsonPrimitive?.contentOrNull,
            style = parsedVariableParams["style"]?.jsonPrimitive?.contentOrNull,
            numImages = parsedVariableParams["numImages"]?.jsonPrimitive?.intOrNull,
            customParams = parsedCustomParams
        )

        LLMModelType.SPEECH_TO_TEXT -> SpeechToTextModelSettings(
            id = id,
            modelId = modelId,
            name = name,
            language = parsedVariableParams["language"]?.jsonPrimitive?.contentOrNull,
            responseFormat = parsedVariableParams["responseFormat"]?.jsonPrimitive?.contentOrNull,
            prompt = parsedVariableParams["prompt"]?.jsonPrimitive?.contentOrNull,
            customParams = parsedCustomParams
        )

        LLMModelType.TEXT_TO_SPEECH -> TextToSpeechModelSettings(
            id = id,
            modelId = modelId,
            name = name,
            voice = parsedVariableParams["voice"]?.jsonPrimitive?.contentOrNull,
            responseFormat = parsedVariableParams["responseFormat"]?.jsonPrimitive?.contentOrNull,
            speed = parsedVariableParams["speed"]?.jsonPrimitive?.floatOrNull,
            customParams = parsedCustomParams
        )

        LLMModelType.AQA -> AqaModelSettings(
            id = id,
            modelId = modelId,
            name = name,
            maxSources = parsedVariableParams["maxSources"]?.jsonPrimitive?.intOrNull,
            answerabilityThreshold = parsedVariableParams["answerabilityThreshold"]?.jsonPrimitive?.floatOrNull,
            customParams = parsedCustomParams
        )
    }
}
