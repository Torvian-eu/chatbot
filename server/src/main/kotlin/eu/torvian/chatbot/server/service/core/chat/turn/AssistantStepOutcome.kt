package eu.torvian.chatbot.server.service.core.chat.turn

import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.server.service.llm.LLMCompletionResult
import kotlinx.serialization.json.JsonObject

/**
 * Carries the assistant step result needed by the shared tool loop.
 *
 * @property assistantMessage Persisted assistant message of the current iteration; its content is what the
 *            loop replays back into the next LLM context.
 * @property toolCallRequests Tool calls requested by the assistant.
 * @property reasoningItems Replay-safe reasoning items emitted with the assistant step, forwarded so the
 *            next follow-up LLM request can replay chain-of-thought. Opaque payload; never logged or rendered.
 * @property endTurnAfterToolCalls Whether the loop must end the turn once the [toolCallRequests] of this step have
 *            been persisted and executed. Set when the step recorded a tool-call-limit failure on its message while
 *            still handing calls over (the iteration bound keeps every call, the argument cap keeps every call whose
 *            argument fits): those calls run, and the flagged message is the last one the turn may produce, so no
 *            follow-up LLM call may consume their results. Never set when the caps leave nothing to execute,
 *            because the step already ended the turn in that case.
 */
internal data class AssistantStepOutcome(
    val assistantMessage: ChatMessage.AssistantMessage,
    val toolCallRequests: List<LLMCompletionResult.CompletionChoice.ToolCallRequest>,
    val reasoningItems: List<JsonObject>? = null,
    val endTurnAfterToolCalls: Boolean = false
)
