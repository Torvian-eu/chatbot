package eu.torvian.chatbot.server.service.core.chat.turn

import eu.torvian.chatbot.server.data.dao.AssistantMessageCompletionState
import eu.torvian.chatbot.server.service.llm.toolCallArgumentLimitExceededCompletionState
import eu.torvian.chatbot.server.service.llm.toolCallIterationLimitExceededCompletionState
import eu.torvian.chatbot.server.service.llm.toolCallsPerStepLimitExceededCompletionState

/**
 * Limits that bound the output and the tool calls of one conversation turn, together with the failure each reached
 * limit produces.
 *
 * The limits live in this leaf rather than in the turn orchestrator's companion (`DefaultConversationTurnOrchestrator`)
 * because every collaborator of a turn needs them — the orchestrator's loop, the collector that accumulates the
 * streamed calls, and both assistant step runners — while the orchestrator itself owns and constructs those
 * collaborators. Keeping them here (this object depends only on the shared failure mapping in `service.llm`) gives
 * the package a single dependency direction instead of a cycle through the orchestrator, and keeps the values and
 * the policy that reads them in one place.
 */
internal object ConversationTurnLimits {

    /**
     * Upper bound of assistant/tool iterations performed in one turn, i.e. the number of primary LLM calls a turn
     * may make while the model keeps requesting tool calls.
     *
     * The bound is the turn-level safety valve against an unbounded agentic loop. Reaching it is reported as a
     * failure on the message of the final allowed iteration (`TOOL_CALL_ITERATION_LIMIT_EXCEEDED`) instead of
     * ending the turn silently; the tool calls of that iteration are still executed, and the turn ends once they
     * have run, because no follow-up call could consume their results.
     */
    const val MAX_TOOL_CALLING_ITERATIONS: Int = 200

    /**
     * Maximum character length of persisted assistant text.
     *
     * Exceeding it is a failure, not a completion with a notice: the message is recorded as incomplete with the
     * `OUTPUT_LIMIT_EXCEEDED` error code and the turn ends before any tool call is persisted or executed,
     * because a response cut off mid-generation is untrustworthy input for side-effecting tools.
     */
    const val MAX_ASSISTANT_MESSAGE_CHARS: Int = 64_000

    /**
     * Maximum character length of one tool call's argument payload, counted over the accumulated argument text of
     * that call (streamed deltas of the same call share the budget, and the payload of a non-streaming/Responses
     * response is bounded the same way).
     *
     * The cap bounds what a single tool input may cost the server in memory, persistence and relay traffic;
     * whole-file arguments of `write_file` legitimately reach six figures, which is why the value is generous.
     * Arguments are JSON, so a call whose argument has to be cut is no longer the input the model sent: the step
     * records `TOOL_CALL_ARGUMENT_LIMIT_EXCEEDED` on its assistant message and drops that call, while every other
     * call of the step is still executed — a call with an over-long argument does not make the other calls of the
     * same response invalid.
     */
    const val MAX_TOOL_CALL_ARGUMENT_CHARS: Int = 300_000

    /**
     * Maximum number of tool calls accepted from a single assistant step, counted per sequential tool-call index
     * within one assistant response (the call ordinal of that response, not the provider's raw output index), so it
     * bounds one assistant response, not the turn or the session.
     *
     * The cap bounds one approval/execution round and keeps a single response from spawning an unbounded number of
     * tool runs; the value leaves room for MCP tools that batch their work. A step that asks for more calls than
     * this is not acted upon at all — a batch that size means something went wrong with the response — so the step
     * records `TOOL_CALLS_PER_STEP_LIMIT_EXCEEDED` on its assistant message, drops every call of that step
     * (including the ones within the cap) and ends the turn without executing anything.
     */
    const val MAX_TOOL_CALLS_PER_STEP: Int = 40

    /**
     * Selects the failure recorded on an assistant step that reached one of the tool-call limits.
     *
     * Each limit has its own execution policy, applied by the step (and by the collector for the calls it
     * accumulates): the iteration bound keeps every call of the final step, the per-step cap drops the whole batch,
     * and the argument cap drops precisely the calls whose argument had to be cut.
     * Precedence when several limits are reached in the same response is per-step call count, then argument size,
     * then the turn's iteration bound, so the limit of the response itself is reported before the turn-level one.
     *
     * @param droppedToolCallCount Tool calls the per-step cap refused as a whole.
     * @param clippedToolCallArgumentCount Tool calls whose argument payload the argument cap had to cut.
     * @param isLastToolCallingIteration Whether the loop may not run another iteration after the step.
     * @param requestsToolCalls Whether the step's response is a tool-calling one; the iteration bound is only a
     *        failure for a step whose tool calls could not have been followed up.
     * @return Failure state to persist with the step's message, or `null` when no tool-call limit was reached.
     */
    fun toolCallLimitFailure(
        droppedToolCallCount: Int,
        clippedToolCallArgumentCount: Int,
        isLastToolCallingIteration: Boolean,
        requestsToolCalls: Boolean
    ): AssistantMessageCompletionState? = when {
        droppedToolCallCount > 0 ->
            toolCallsPerStepLimitExceededCompletionState(MAX_TOOL_CALLS_PER_STEP)

        clippedToolCallArgumentCount > 0 ->
            toolCallArgumentLimitExceededCompletionState(MAX_TOOL_CALL_ARGUMENT_CHARS)

        isLastToolCallingIteration && requestsToolCalls ->
            toolCallIterationLimitExceededCompletionState(MAX_TOOL_CALLING_ITERATIONS)

        else -> null
    }
}
