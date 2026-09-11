package eu.torvian.chatbot.server.service.llm

import eu.torvian.chatbot.common.models.llm.ChatModelSettings
import eu.torvian.chatbot.common.models.llm.ModelSettings
import eu.torvian.chatbot.common.models.llm.ResponsesModelSettings

/**
 * Shared helpers describing the chat capability of a [ModelSettings] profile.
 *
 * The chat-capability question is asked at several independent places — attaching a model preset to an
 * agent role, preparing a conversation turn, and validating auxiliary compaction configuration — and
 * every one of them must agree on which profile types are conversational, so the logic lives here
 * instead of being duplicated. Only [ChatModelSettings] and [ResponsesModelSettings] describe
 * conversational generation; the remaining profile types (embeddings, images, speech, ...) cannot
 * drive a chat request.
 */

/**
 * Whether the given [ModelSettings] is chat-capable (CHAT or RESPONSES).
 *
 * @param settings The settings profile to inspect.
 * @return `true` for chat-capable settings, `false` otherwise.
 */
internal fun isChatLikeSettings(settings: ModelSettings): Boolean = when (settings) {
    is ChatModelSettings -> true
    is ResponsesModelSettings -> true
    else -> false
}

/**
 * Extracts the streaming flag from chat-capable settings.
 *
 * @param settings The settings profile to inspect.
 * @return The `stream` flag when the settings are chat-capable, otherwise `null`.
 */
internal fun chatStreamFlag(settings: ModelSettings): Boolean? = when (settings) {
    is ChatModelSettings -> settings.stream
    is ResponsesModelSettings -> settings.stream
    else -> null
}
