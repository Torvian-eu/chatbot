package eu.torvian.chatbot.server.service.core.chat.turn

import eu.torvian.chatbot.common.models.core.ChatSession
import eu.torvian.chatbot.common.models.core.FileReference
import eu.torvian.chatbot.server.service.core.LLMConfig
import eu.torvian.chatbot.server.service.core.toolcall.ToolCallApprovalSubmission
import kotlinx.coroutines.flow.Flow

/**
 * Carries all validated inputs required to execute one assistant turn.
 *
 * @property userId User whose approval preferences apply to tool execution.
 * @property session Session whose message thread is being continued.
 * @property llmConfig Resolved provider/model/settings/tool configuration.
 * @property content New user message content, or `null` for branch-and-continue mode.
 * @property parentMessageId Existing parent to continue from when branching or replying.
 * @property fileReferences File references attached to the new user message.
 * @property toolApprovalFlow Client approval submissions for tool calls that need confirmation.
 */
data class ConversationTurnRequest(
    val userId: Long,
    val session: ChatSession,
    val llmConfig: LLMConfig,
    val content: String?,
    val parentMessageId: Long?,
    val fileReferences: List<FileReference>,
    val toolApprovalFlow: Flow<ToolCallApprovalSubmission>
)