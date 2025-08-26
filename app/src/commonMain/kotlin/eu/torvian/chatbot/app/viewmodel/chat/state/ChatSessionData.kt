package eu.torvian.chatbot.app.viewmodel.chat.state

import eu.torvian.chatbot.common.models.ChatModelSettings
import eu.torvian.chatbot.common.models.ChatSession
import eu.torvian.chatbot.common.models.LLMModel

/**
 * Combined data structure containing a chat session and its associated model settings.
 * This provides a cohesive state object that includes both session information and
 * the settings needed for message processing.
 *
 * @property session The chat session containing messages and metadata.
 * @property llmModel The LLM model for this session, or null if not available/loaded.
 * @property modelSettings The model settings for this session, or null if not available/loaded.
 */
data class ChatSessionData(
    val session: ChatSession,
    val llmModel: LLMModel? = null,
    val modelSettings: ChatModelSettings? = null,
)