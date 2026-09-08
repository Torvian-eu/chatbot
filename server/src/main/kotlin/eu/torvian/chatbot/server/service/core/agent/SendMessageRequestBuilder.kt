package eu.torvian.chatbot.server.service.core.agent

import arrow.core.Either
import eu.torvian.chatbot.common.models.agent.SendMessageRequest
import eu.torvian.chatbot.common.models.tool.ToolCall
import eu.torvian.chatbot.server.service.core.error.agent.SendMessageRequestBuildError

/**
 * Builds the tool-specific [SendMessageRequest] payload for a `send_message` operator-tool call.
 *
 * The builder owns input parsing (extracting `chat_session_id`, `message`, and `mode` from the
 * LLM-provided arguments JSON) and the user-scoped target-session validation (existence +
 * same-user ownership), producing the typed payload that the operator executor serializes into the
 * generic relay envelope. Keeping this logic separate from the transport-focused
 * [eu.torvian.chatbot.server.service.builtin.OperatorToolExecutor] makes the validation a pure,
 * unit-testable service.
 */
interface SendMessageRequestBuilder {

    /**
     * Builds a send-message request after validating that the target session exists and is owned by
     * [userId].
     *
     * @param userId User owning the calling session; also the ownership scope for the target.
     * @param toolCall Persisted operator call containing untrusted target arguments.
     * @return A validated request or a logical build error.
     */
    suspend fun build(
        userId: Long,
        toolCall: ToolCall
    ): Either<SendMessageRequestBuildError, SendMessageRequest>
}