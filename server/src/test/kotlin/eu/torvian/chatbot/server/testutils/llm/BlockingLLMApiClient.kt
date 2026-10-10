package eu.torvian.chatbot.server.testutils.llm

import arrow.core.Either
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.LLMProvider
import eu.torvian.chatbot.common.models.llm.ModelSettings
import eu.torvian.chatbot.common.models.tool.ToolDefinition
import eu.torvian.chatbot.server.service.llm.LLMApiClient
import eu.torvian.chatbot.server.service.llm.LLMApiClientStub
import eu.torvian.chatbot.server.service.llm.LLMCompletionError
import eu.torvian.chatbot.server.service.llm.LLMCompletionResult
import eu.torvian.chatbot.server.service.llm.RawChatMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation

/**
 * [LLMApiClient] whose non-streaming completion never returns until it is cancelled.
 *
 * Stands in for a long-running auxiliary compaction call: the test can wait for [started] to know the
 * call is in flight and for [cancelled] to know the caller aborted it, which is what makes a
 * cancellation assertion deterministic instead of a race against an instant stub response.
 *
 * @property delegate Client answering the methods this double does not override.
 */
class BlockingLLMApiClient(
    private val delegate: LLMApiClient = LLMApiClientStub()
) : LLMApiClient by delegate {

    /** Completed as soon as the auxiliary completion was requested. */
    val started: CompletableDeferred<Unit> = CompletableDeferred()

    /** Completed when the in-flight auxiliary completion was cancelled. */
    val cancelled: CompletableDeferred<Unit> = CompletableDeferred()

    override suspend fun completeChat(
        messages: List<RawChatMessage>,
        modelConfig: LLMModel,
        provider: LLMProvider,
        settings: ModelSettings,
        apiKey: String?,
        tools: List<ToolDefinition>?,
        systemMessage: String?
    ): Either<LLMCompletionError, LLMCompletionResult> {
        started.complete(Unit)
        try {
            // Suspends until the call is cancelled; no completion is ever produced.
            awaitCancellation()
        } finally {
            cancelled.complete(Unit)
        }
    }
}
