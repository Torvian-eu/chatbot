package eu.torvian.chatbot.common.models.llm

import kotlinx.serialization.json.JsonObject

/**
 * Defines common, well-known capability keys that might be present in the
 * [LLMModel.capabilities] [JsonObject].
 *
 * Using these constants provides type-safety and discoverability when querying
 * capabilities using the extension functions on [LLMModel].
 */
object LLMModelCapabilities {
    /** Key for the capability indicating the model can process images as part of its input context. */
    const val MULTIMODAL_IMAGE_INPUT = "MULTIMODAL_IMAGE_INPUT"
    /** Key for the capability indicating the model can process audio as part of its input context. */
    const val MULTIMODAL_AUDIO_INPUT = "MULTIMODAL_AUDIO_INPUT"
    /** Key for the capability indicating the model can process video as part of its input context. */
    const val MULTIMODAL_VIDEO_INPUT = "MULTIMODAL_VIDEO_INPUT"

    /** Key for the capability indicating the model supports and can interpret structured tool/function definitions. */
    const val TOOL_CALLING = "TOOL_CALLING"
    /** Key for the capability indicating the model can be configured to output structured JSON. */
    const val JSON_OUTPUT = "JSON_OUTPUT"
    /** Key for the capability indicating the model supports streaming responses. */
    const val STREAMING_OUTPUT = "STREAMING_OUTPUT"

    /** Key for the capability indicating the model supports generating responses with explicit reasoning steps. */
    const val THINKING_PROCESS = "THINKING_PROCESS"
    /** Key for the capability indicating the model incorporates specific content filtering or safety features. */
    const val SAFETY_CONTROLS = "SAFETY_CONTROLS"

}