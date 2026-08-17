package eu.torvian.chatbot.app.utils.misc

import eu.torvian.chatbot.common.models.llm.ChatModelSettings
import eu.torvian.chatbot.common.models.llm.ModelSettings
import eu.torvian.chatbot.common.models.llm.ResponsesModelSettings

/**
 * Resolves whether streaming is enabled for a chat-capable settings profile.
 *
 * Both [ChatModelSettings] and [ResponsesModelSettings] are valid chat-capable profiles and expose
 * their own `stream` flag, so this resolves the concrete subtype before reading the value.
 *
 * @receiver The resolved chat-capable settings profile, or null if no profile is active.
 * @return True when streaming is enabled, false when disabled or no profile is active.
 */
fun ModelSettings?.isStreamingEnabled(): Boolean = when (this) {
    is ChatModelSettings -> stream
    is ResponsesModelSettings -> stream
    else -> false
}
