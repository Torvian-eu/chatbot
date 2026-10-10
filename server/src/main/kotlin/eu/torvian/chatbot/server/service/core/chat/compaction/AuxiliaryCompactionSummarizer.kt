package eu.torvian.chatbot.server.service.core.chat.compaction

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.right
import eu.torvian.chatbot.server.service.core.LLMConfig
import eu.torvian.chatbot.server.service.llm.LLMApiClient
import eu.torvian.chatbot.server.service.llm.LLMCompletionError
import eu.torvian.chatbot.server.service.llm.LLMCompletionResult
import eu.torvian.chatbot.server.service.llm.RawChatMessage
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Total timeout (in seconds) around the entire retrying auxiliary non-streaming call,
 * including `RetryLLMApiClient` backoff. An operational bound, not a product decision.
 */
internal const val AUXILIARY_COMPACTION_TIMEOUT_SECONDS: Long = 180L

/**
 * Maximum characters of a provider error message kept for the public failure surface.
 */
private const val MAX_ERROR_MESSAGE_CHARS: Int = 300

/**
 * Runs the auxiliary compaction model call and validates its output.
 *
 * The auxiliary request is a single non-streaming completion: the compaction instruction is appended as the
 * final user message, the auxiliary settings' optional system prompt passes through as the system message, and
 * no tools are offered. The call is bounded by a total timeout that also covers the retrying client's backoff,
 * and the returned text is accepted only when the provider signalled a complete generation, no tool call was
 * requested and the content is non-blank.
 */
class AuxiliaryCompactionSummarizer(
    private val llmApiClient: LLMApiClient,
    private val auxiliaryTimeout: Duration = AUXILIARY_COMPACTION_TIMEOUT_SECONDS.seconds
) {

    /**
     * Summarizes a compaction window with the auxiliary model.
     *
     * @param config The validated auxiliary configuration (no tools; `systemMessage` carries the
     *            compaction settings' optional system prompt, empty when none is set).
     * @param messages The bounded compaction input (the over-threshold window).
     * @param instruction The user's compaction instruction (guaranteed non-blank by the resolver),
     *            appended as the final user message.
     * @return Either a compaction error or the trimmed non-blank summary.
     */
    suspend fun summarize(
        config: LLMConfig,
        messages: List<RawChatMessage>,
        instruction: String
    ): Either<ConversationCompactionError, String> = either {
        validateSummaryOutput(generateSummary(config, messages, instruction).bind()).bind()
    }

    /**
     * Invokes the auxiliary compaction model under a bounded total timeout.
     *
     * The user's compaction instruction travels as the final user message of the request, not as the
     * system message: the instruction reads naturally as the closing user turn that asks the model to
     * summarize the conversation. The config's system message (the preference's optional system prompt)
     * is passed through normally, so the two roles never collide.
     *
     * @param config The validated auxiliary configuration (no tools; `systemMessage` carries the
     *            compaction settings' optional system prompt, empty when none is set).
     * @param messages The bounded compaction input (the over-threshold window).
     * @param instruction The user's compaction instruction (guaranteed non-blank by the resolver),
     *            appended as the final user message.
     * @return Either a compaction error or the raw completion result. A result whose generation the provider
     *         declared incomplete is reported as a generation failure, because a summary cut off mid-generation
     *         would silently replace the messages it covers with partial text.
     */
    private suspend fun generateSummary(
        config: LLMConfig,
        messages: List<RawChatMessage>,
        instruction: String
    ): Either<ConversationCompactionError, LLMCompletionResult> {
        // The instruction is appended unconditionally as the closing user message: it tells the model
        // what to produce and guarantees the request never ends on an assistant (or tool) message —
        // a trailing assistant turn would ask the model to answer itself, which some providers reject
        // with a 400. resolveAuxiliaryConfig already rejects a blank instruction, so the appended
        // message is never empty.
        val auxiliaryMessages = messages + RawChatMessage.User(instruction)
        return try {
            val result = withTimeout(auxiliaryTimeout) {
                llmApiClient.completeChat(
                    messages = auxiliaryMessages,
                    modelConfig = config.model,
                    provider = config.provider,
                    settings = config.settings,
                    apiKey = config.apiKey,
                    tools = null,
                    systemMessage = config.systemMessage.takeIf { it.isNotBlank() }
                )
            }
            // A result that carries a provider-declared ending is not a summary: the provider answered, but it
            // reported that the generation did not complete, so the text is truncated provider output and must
            // never become a summary chunk.
            val providerFailure = result.getOrNull()?.providerFailure
            if (providerFailure != null) {
                return ConversationCompactionError.GenerationFailed(providerFailure.sanitizedMessage()).left()
            }
            // TimeoutCancellationException is caught below; any other CancellationException (external
            // socket cancellation) must propagate as a coroutine cancellation, not a compaction error.
            result.mapLeft { llmError ->
                ConversationCompactionError.GenerationFailed(llmError.sanitizedMessage())
            }
        } catch (_: TimeoutCancellationException) {
            ConversationCompactionError.TimedOut.left()
        }
    }

    /**
     * Extracts a bounded, provider-body-free description from an LLM completion error.
     *
     * @receiver The provider error to describe.
     * @return A short message suitable for the public error surface and logs (never raw bodies).
     */
    private fun LLMCompletionError.sanitizedMessage(): String =
        when (this) {
            is LLMCompletionError.NetworkError -> message
            is LLMCompletionError.ApiError ->
                message?.take(MAX_ERROR_MESSAGE_CHARS) ?: "HTTP $statusCode"

            is LLMCompletionError.InvalidResponseError -> message
            is LLMCompletionError.ProviderFailureError -> message
            is LLMCompletionError.AuthenticationError -> message
            is LLMCompletionError.ConfigurationError -> message
            is LLMCompletionError.OtherError -> message
        }

    /**
     * Validates the auxiliary response and extracts the trimmed summary text.
     *
     * @param result The raw completion result.
     * @return Either [ConversationCompactionError.InvalidOutput] or the trimmed non-blank summary.
     */
    private fun validateSummaryOutput(
        result: LLMCompletionResult
    ): Either<ConversationCompactionError, String> {
        val choice = result.choices.firstOrNull()
            ?: return ConversationCompactionError.InvalidOutput(
                "Compaction model returned no completion choices"
            ).left()
        if (!choice.toolCalls.isNullOrEmpty()) {
            return ConversationCompactionError.InvalidOutput(
                "Compaction model requested tool calls; compaction is a non-tool-calling request"
            ).left()
        }
        val content = choice.content?.trim()
        if (content.isNullOrBlank()) {
            return ConversationCompactionError.InvalidOutput(
                "Compaction model returned blank or empty summary content"
            ).left()
        }
        return content.right()
    }
}
