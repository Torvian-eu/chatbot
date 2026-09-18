package eu.torvian.chatbot.server.service.core.chat.turn

import arrow.core.getOrElse
import eu.torvian.chatbot.common.models.tool.ToolCall
import eu.torvian.chatbot.server.service.builtin.ToolCallExecutionContext
import eu.torvian.chatbot.server.service.core.chat.compaction.ConversationCompactionService
import eu.torvian.chatbot.server.service.core.chat.content.ToolResultContentBuilder
import eu.torvian.chatbot.server.service.core.chat.context.ChatContextBuilder
import eu.torvian.chatbot.server.service.core.chat.context.ConversationContext
import eu.torvian.chatbot.server.service.core.chat.context.SourceMessageSnapshot
import eu.torvian.chatbot.server.service.core.chat.persistence.ConversationTurnPersistence
import eu.torvian.chatbot.server.service.core.toolcall.ToolCallExecutionEvent
import eu.torvian.chatbot.server.service.core.toolcall.ToolCallOrchestrator
import eu.torvian.chatbot.server.service.llm.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * Default implementation that owns the shared assistant/tool loop for a single conversation turn.
 *
 * The loop, turn preparation, tool-call execution and context-replay mapping live here; the mode-specific
 * assistant steps are delegated to [StreamingAssistantStepRunner] and [NonStreamingAssistantStepRunner], which
 * own their own provider call, output/tool-call limit policy and assistant-message finalization.
 *
 * @property llmApiClient Client used for streaming and non-streaming LLM calls.
 * @property toolCallOrchestrator Collaborator that handles approval and tool execution.
 * @property toolResultContentBuilder Serializer for completed tool results appended back into context.
 * @property chatContextBuilder Builder that reconstructs the threaded LLM context.
 * @property conversationTurnPersistence Collaborator that owns message and tool-call persistence workflow.
 * @property reasoningCapabilityRecorder Collaborator that records a model's reasoning mode (encrypted vs
 *            plaintext) from observed reasoning items, used to adapt reasoning replay across model switches.
 * @property conversationCompactionService Policy that runs before every primary LLM call and may replace
 *            the oversized primary context with one labeled synthetic summary.
 */
class DefaultConversationTurnOrchestrator(
    private val llmApiClient: LLMApiClient,
    private val toolCallOrchestrator: ToolCallOrchestrator,
    private val toolResultContentBuilder: ToolResultContentBuilder,
    private val chatContextBuilder: ChatContextBuilder,
    private val conversationTurnPersistence: ConversationTurnPersistence,
    private val reasoningCapabilityRecorder: ReasoningCapabilityRecorder,
    private val conversationCompactionService: ConversationCompactionService,
) : ConversationTurnOrchestrator {

    /** Collects the provider chunk stream of a streaming assistant step and fills the streamed state. */
    private val llmStreamCollector = LlmStreamCollector(llmApiClient)

    /**
     * Runs the streaming assistant step, delegating its chunk stream to [llmStreamCollector] and sharing the
     * orchestrator's persistence and reasoning-recorder collaborators.
     */
    private val streamingStep = StreamingAssistantStepRunner(
        llmStreamCollector,
        conversationTurnPersistence,
        reasoningCapabilityRecorder
    )

    /** Runs the non-streaming assistant step: full-response call, output-cap cut-off and message persistence. */
    private val nonStreamingStep = NonStreamingAssistantStepRunner(
        llmApiClient,
        conversationTurnPersistence,
        reasoningCapabilityRecorder
    )

    companion object {
        /** Logger used for turn-runtime diagnostics. */
        private val logger: Logger = LogManager.getLogger(DefaultConversationTurnOrchestrator::class.java)
    }

    /**
     * Processes a non-streaming turn by delegating the assistant step to the full-response LLM path.
     *
     * @param request Immutable input bundle for the turn being processed.
     * @return Flow of internal lifecycle events for the turn.
     */
    override fun processNonStreamingTurn(request: ConversationTurnRequest): Flow<ConversationTurnEvent> = flow {
        processTurn(
            request = request,
            // The step is passed as an explicit lambda instead of a callable reference so the suspended
            // five-parameter signature (whose last parameter is itself a suspend lambda) is unambiguous.
            processAssistantStep = { turnRequest, currentContext, parentMessageId, isLastToolCallingIteration, eventSink ->
                nonStreamingStep.run(turnRequest, currentContext, parentMessageId, isLastToolCallingIteration, eventSink)
            },
            emit = { event -> emit(event) }
        )
    }

    /**
     * Processes a streaming turn by delegating the assistant step to the chunked LLM path.
     *
     * @param request Immutable input bundle for the turn being processed.
     * @return Flow of internal lifecycle events for the turn.
     */
    override fun processStreamingTurn(request: ConversationTurnRequest): Flow<ConversationTurnEvent> = flow {
        processTurn(
            request = request,
            processAssistantStep = { turnRequest, currentContext, parentMessageId, isLastToolCallingIteration, eventSink ->
                streamingStep.run(turnRequest, currentContext, parentMessageId, isLastToolCallingIteration, eventSink)
            },
            emit = { event -> emit(event) }
        )
    }

    /**
     * Runs the shared turn lifecycle, independent of assistant delivery mode.
     *
     * @param request Immutable input bundle for the turn being processed.
     * @param processAssistantStep Mode-specific assistant generation function, called with the context and parent
     *        of the iteration it is about to perform and told whether the tool-calling bound leaves room for
     *        another iteration after it. A step of the last allowed iteration records the iteration-limit failure
     *        on its own message and asks the loop to end the turn once its tool calls have run (see the step
     *        runners), which is why the loop never exits on the bound itself.
     * @param emit Sink used to publish lifecycle events.
     */
    private suspend fun processTurn(
        request: ConversationTurnRequest,
        processAssistantStep: suspend (
            request: ConversationTurnRequest,
            currentContext: List<RawChatMessage>,
            parentMessageId: Long,
            isLastToolCallingIteration: Boolean,
            emit: suspend (ConversationTurnEvent) -> Unit
        ) -> AssistantStepOutcome?,
        emit: suspend (ConversationTurnEvent) -> Unit
    ) {
        val preparedTurn = prepareTurn(request, emit)
        var lastMessageId = preparedTurn.lastMessageId
        var iterationCount = 0

        // The full identity-bearing source context is handed to the compaction state once at turn
        // start: it initializes the rolling window (eligible prior summary + delta, or the full
        // thread) and the identity ledger, after which the full uncompressed content is released —
        // the window (one optional summary + additional uncompressed messages) and the ledger are the
        // loop's only conversation state from here on. A structurally invalid preference fails here
        // (InvalidConfiguration left) and aborts the turn before any counting or primary call.
        val compactionState = conversationCompactionService.beginTurn(
            userId = request.userId,
            sessionId = request.session.id,
            initialUnits = preparedTurn.conversationContext.units
        ).getOrElse { error ->
            logger.error(
                "Conversation compaction setup failed for session ${request.session.id}: $error"
            )
            emit(ConversationTurnEvent.CompactionFailed(error))
            emit(ConversationTurnEvent.TurnCompleted)
            return
        }

        // A step that reached a tool-call limit ends the turn itself once its calls have run, so this condition is
        // the backstop that keeps a step violating that contract from looping forever.
        while (iterationCount < ConversationTurnLimits.MAX_TOOL_CALLING_ITERATIONS &&
            !request.turnControlSignal.isCancelled
        ) {
            if (request.turnControlSignal.isPaused) {
                logger.info(
                    "Turn paused for session ${request.session.id}; halting before next LLM iteration"
                )
                emit(ConversationTurnEvent.TurnCompleted)
                break
            }

            // Exact compaction integration point: runs once for the initial primary call and once for
            // every post-tool primary call. A failure terminates the turn before any primary request.
            val preflight = conversationCompactionService.preparePrimaryContext(
                state = compactionState,
                primaryConfig = request.llmConfig,
                expectedLeafMessageId = lastMessageId
            ).getOrElse { error ->
                logger.error(
                    "Conversation compaction preflight failed for session ${request.session.id}: $error"
                )
                emit(ConversationTurnEvent.CompactionFailed(error))
                emit(ConversationTurnEvent.TurnCompleted)
                break
            }

            iterationCount++
            // The notification is emitted only when this preflight persisted a chunk and the turn goes on to the
            // primary call; emitting it immediately before that call keeps the event tied to actual usage, and
            // nothing is emitted on the paths that reuse or skip the summary (no persisted chunk).
            preflight.persistedChunkIfAny?.let { persistedChunk ->
                emit(ConversationTurnEvent.CompactionCompleted(persistedChunk))
            }
            // preflight.primaryMessages is the rolling window verified to fit the threshold: the
            // original flattened thread (first preflight, raw fits), the hybrid [summary] + additional
            // uncompressed messages (the steady state), or the summary message alone right after a
            // compaction (the user's compaction instruction is expected to make that summary
            // self-contained enough to continue from).
            val assistantStep = processAssistantStep(
                request,
                preflight.primaryMessages,
                lastMessageId,
                // This iteration is the last one the bound allows: the step may not hand tool calls back,
                // because no follow-up call could consume their results, so it records the iteration-limit
                // failure on its own message and ends the turn (breaking the loop on its null outcome).
                iterationCount >= ConversationTurnLimits.MAX_TOOL_CALLING_ITERATIONS,
                emit
            ) ?: break
            lastMessageId = assistantStep.assistantMessage.id

            val pendingToolCalls = conversationTurnPersistence.persistPendingToolCalls(
                messageId = assistantStep.assistantMessage.id,
                toolCallRequests = assistantStep.toolCallRequests,
                enabledTools = request.llmConfig.tools
            )
            emit(ConversationTurnEvent.ToolCallsReceived(pendingToolCalls))

            val completedToolCalls = executeToolCalls(request, pendingToolCalls, emit)
            // Grow the rolling window with the newly completed assistant/tool unit so the next
            // preflight covers it (the follow-up iteration sees the appended unit; the service's own
            // window/ledger state is updated by the preflight itself, so there is no record hook).
            compactionState.appendUnit(
                source = SourceMessageSnapshot(
                    id = assistantStep.assistantMessage.id,
                    updatedAt = assistantStep.assistantMessage.updatedAt
                ),
                rawMessages = assistantAndToolResultMessages(
                    assistantStep = assistantStep,
                    completedToolCalls = completedToolCalls,
                    reasoningModelId = request.llmConfig.model.id
                )
            )

            if (assistantStep.endTurnAfterToolCalls) {
                // The step reached a tool-call limit and flagged its own message. Its calls have just been executed
                // (a reached limit never suppresses execution), and the flagged message is the turn's last one, so
                // the turn ends here instead of starting a follow-up iteration that the limit forbids.
                logger.warn(
                    "Turn for session ${request.session.id} ended after the tool calls of assistant message " +
                        "${assistantStep.assistantMessage.id} (${assistantStep.assistantMessage.errorCode})"
                )
                emit(ConversationTurnEvent.TurnCompleted)
                break
            }
        }
    }

    /**
     * Performs shared setup before the assistant/tool loop starts.
     *
     * @param request Immutable input bundle for the turn being processed.
     * @param emit Sink used to publish lifecycle events.
     * @return Initial loop state containing the parent message anchor and built context.
     */
    private suspend fun prepareTurn(
        request: ConversationTurnRequest,
        emit: suspend (ConversationTurnEvent) -> Unit
    ): PreparedTurnState {
        var lastMessageId: Long
        val updatedSessionMessages = if (request.content != null) {
            val userMessage = conversationTurnPersistence.saveUserMessage(
                sessionId = request.session.id,
                content = request.content,
                parentMessageId = request.parentMessageId,
                fileReferences = request.fileReferences
            ).let { persistedUserMessage ->
                emit(
                    ConversationTurnEvent.UserMessageSaved(
                        persistedUserMessage.userMessage,
                        persistedUserMessage.updatedParentMessage
                    )
                )
                persistedUserMessage.userMessage
            }
            lastMessageId = userMessage.id
            request.session.messages + userMessage
        } else {
            val parentMessageId = request.parentMessageId
                ?: throw IllegalStateException("parentMessageId is null in Branch & Continue mode")
            lastMessageId = parentMessageId
            request.session.messages
        }

        val sessionToolCalls = conversationTurnPersistence.loadSessionToolCalls(request.session.id)
        val conversationContext = chatContextBuilder.buildContext(
            startingMessageId = lastMessageId,
            sessionMessages = updatedSessionMessages,
            toolCalls = sessionToolCalls
        )

        return PreparedTurnState(lastMessageId = lastMessageId, conversationContext = conversationContext)
    }

    /**
     * Executes persisted tool calls and mirrors the tool orchestrator's lifecycle back into turn events.
     *
     * @param request Immutable input bundle for the turn being processed.
     * @param pendingToolCalls Persisted tool calls awaiting execution.
     * @param emit Sink used to publish lifecycle events.
     * @return Completed tool calls that should be appended back into the LLM context.
     */
    private suspend fun executeToolCalls(
        request: ConversationTurnRequest,
        pendingToolCalls: List<ToolCall>,
        emit: suspend (ConversationTurnEvent) -> Unit
    ): List<ToolCall> {
        val completedToolCalls = mutableListOf<ToolCall>()
        // Prepared production turns always carry a role: turn preparation rejects role-less sessions,
        // so a missing role here is an integration error. A zero fallback would silently fail every
        // operator allow-list lookup in the spawn builder, so fail loudly instead.
        val requestingAgentRoleId = request.session.agentRoleId
            ?: throw IllegalStateException(
                "Cannot execute tool calls for session ${request.session.id}: no agent role selected"
            )
        // Bundle the caller identity with the turn's session context into the single execution
        // context consumed by the whole approval/execution chain. The session and the validated
        // agent role are guaranteed above (the role is non-null; the project is nullable because a
        // session may have no project selected). Server built-in tool handlers and the operator
        // tool executor receive this object to resolve session/role/project identity — the project
        // scope in particular lets spawn_agent resolve its target role within the session's project.
        val sessionContext = ToolCallExecutionContext(
            userId = request.userId,
            sessionId = request.session.id,
            sessionName = request.session.name,
            agentRoleId = requestingAgentRoleId,
            projectId = request.session.projectId
        )
        val executionEvents = toolCallOrchestrator.executeAndUpdateToolCalls(
            sessionContext,
            pendingToolCalls,
            request.llmConfig.tools,
            request.toolApprovalFlow,
            request.operatorToolResultFlow,
            request.turnControlSignal
        )
        executionEvents.collect { event ->
            when (event) {
                is ToolCallExecutionEvent.ToolCallExecuting -> {
                    emit(ConversationTurnEvent.ToolCallExecuting(event.toolCall))
                }

                is ToolCallExecutionEvent.ToolCallCompleted -> {
                    completedToolCalls.add(event.toolCall)
                    emit(ConversationTurnEvent.ToolExecutionCompleted(event.toolCall))
                }

                is ToolCallExecutionEvent.ToolCallApprovalRequested -> {
                    emit(ConversationTurnEvent.ToolCallApprovalRequested(event.toolCall))
                }

                is ToolCallExecutionEvent.OperatorToolExecutionRequested -> {
                    emit(
                        ConversationTurnEvent.OperatorToolExecutionRequested(
                            toolCallId = event.toolCallId,
                            toolName = event.toolName,
                            payload = event.payloadJson
                        )
                    )
                }
            }
        }
        return completedToolCalls
    }

    /**
     * Derives the provider-facing raw messages for one completed assistant source unit.
     *
     * @param assistantStep Completed assistant step whose content and reasoning enter the unit.
     * @param completedToolCalls Completed tool calls whose calls and results should be appended.
     * @param reasoningModelId ID of the model that produced the step's reasoning items (the current
     *            turn's model), used to gate encrypted reasoning replay on the follow-up LLM request.
     * @return The ordered raw messages (assistant message followed by its tool results) appended as one
     *         source unit, so compaction can never split the call from its results.
     */
    private fun assistantAndToolResultMessages(
        assistantStep: AssistantStepOutcome,
        completedToolCalls: List<ToolCall>,
        reasoningModelId: Long?
    ): List<RawChatMessage> {
        // Derive both provider messages from the same ordered collection so a result can never
        // be emitted without its matching assistant tool call. Every recorded call is replayed,
        // so a tool-calling assistant step always carries its full set of calls in context.
        val assistantContextMessage = RawChatMessage.Assistant(
            // The persisted content is what the follow-up context replays.
            content = assistantStep.assistantMessage.content,
            toolCalls = completedToolCalls.map { toolCall ->
                RawChatMessage.Assistant.ToolCall(
                    id = toolCall.toolCallId,
                    name = toolCall.toolName,
                    arguments = toolCall.input
                )
            },
            reasoningItems = assistantStep.reasoningItems,
            reasoningModelId = reasoningModelId
        )
        // A provider transcript must not contain a result without its replayed assistant call.
        val toolResultMessages = completedToolCalls.map { toolCall ->
            RawChatMessage.Tool(
                content = toolResultContentBuilder.build(toolCall),
                toolCallId = toolCall.toolCallId ?: "",
                name = toolCall.toolName
            )
        }

        return listOf(assistantContextMessage) + toolResultMessages
    }

    /**
     * Carries the initial loop state after user persistence and context reconstruction.
     *
     * @property lastMessageId Message that anchors the next assistant reply.
     * @property conversationContext Reconstructed identity-bearing source context for the turn.
     */
    private data class PreparedTurnState(
        val lastMessageId: Long,
        val conversationContext: ConversationContext
    )
}