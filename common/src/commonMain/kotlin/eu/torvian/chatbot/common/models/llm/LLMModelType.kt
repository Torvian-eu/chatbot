package eu.torvian.chatbot.common.models.llm

import kotlinx.serialization.Serializable

/**
 * Defines the operational type or primary function of an LLM model.
 * This distinction is crucial for determining the expected API interaction,
 * input format, output format, and overall capability of the model within the application.
 */
@Serializable
enum class LLMModelType {
    /**
     * Models primarily designed for multi-turn, conversational text generation.
     * They can often process multimodal inputs (e.g., text and images) to produce text outputs.
     */
    CHAT,

    /**
     * Models used through OpenAI's Responses API (`POST /v1/responses`). These models support native
     * reasoning tokens whose summaries and encrypted content can be replayed across multi-turn conversations
     * for higher-accuracy reasoning results.
     */
    RESPONSES,

    /**
     * Older models intended for single-turn text completion, where the model continues a given prompt.
     * Largely superseded by [CHAT] models for most generative tasks.
     */
    COMPLETION,

    /**
     * Models that convert text into numerical vector representations (embeddings),
     * used for semantic similarity, search, and Retrieval-Augmented Generation (RAG).
     */
    EMBEDDING,

    /**
     * Models that generate images based on textual descriptions.
     */
    IMAGE_GENERATION,

    /**
     * Models that transcribe spoken audio into written text.
     */
    SPEECH_TO_TEXT,

    /**
     * Models that synthesize spoken audio from written text.
     */
    TEXT_TO_SPEECH,

    /**
     * Specialized models for Attributed Question Answering (AQA), which provide answers
     * grounded in provided sources, often with answerability scores.
     */
    AQA,

}