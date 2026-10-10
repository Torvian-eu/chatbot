package eu.torvian.chatbot.server.service.core.chat.compaction

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.withError
import eu.torvian.chatbot.common.models.api.core.CompactionSkipReason
import eu.torvian.chatbot.server.data.dao.ConversationCompactionChunkDao
import eu.torvian.chatbot.server.service.core.chat.context.ChatContextBuilder
import eu.torvian.chatbot.server.service.core.chat.context.SourceMessageSnapshot
import eu.torvian.chatbot.server.service.core.chat.persistence.ConversationTurnPersistence
import eu.torvian.chatbot.server.service.core.chat.preparation.ConversationTurnPreparationService
import eu.torvian.chatbot.server.service.core.error.message.ValidateNewMessageError

/**
 * Default [ConversationManualCompactionService].
 *
 * The session runtime is resolved through the turn-preparation service, so a manual compaction uses
 * exactly the model, settings, tools, system prompt and effective compaction configuration a turn on
 * that session would use; nothing is re-derived here. The displayed thread is rebuilt with the shared
 * context builder, which yields the same identity-bearing units a turn would build, so the persisted
 * chunk is interchangeable with an automatically created one.
 *
 * @property preparationService Resolves the session runtime and its effective compaction configuration.
 * @property conversationTurnPersistence Loads the session's tool calls for thread reconstruction.
 * @property chatContextBuilder Rebuilds the identity-bearing thread ending at the session leaf.
 * @property chunkDao Loads the retained chunks that decide eligibility and largest-coverage selection.
 * @property compactionService Performs the one-shot summarization and the verified chunk insert.
 */
class DefaultConversationManualCompactionService(
    private val preparationService: ConversationTurnPreparationService,
    private val conversationTurnPersistence: ConversationTurnPersistence,
    private val chatContextBuilder: ChatContextBuilder,
    private val chunkDao: ConversationCompactionChunkDao,
    private val compactionService: ConversationCompactionService
) : ConversationManualCompactionService {

    override suspend fun compactThread(
        userId: Long,
        sessionId: Long
    ): Either<ConversationCompactionError, ManualCompactionOutcome> = either {
        val prepared = withError({ validationError: ValidateNewMessageError ->
            ConversationCompactionError.InvalidConfiguration(validationError.toCompactionReason(sessionId))
        }) {
            preparationService.prepareSessionRuntime(userId, sessionId).bind()
        }

        // Manual compaction is available whenever the session can run at all: it is not gated by
        // automatic compaction, whose flags live on the preset and the preference. Only the auxiliary
        // configuration decides whether the request can be served, and an unusable one is reported with
        // its own reason.
        val settings = when (val resolved = prepared.resolvedCompaction) {
            is ResolvedCompactionConfig.Usable -> resolved.settings

            is ResolvedCompactionConfig.Unusable -> raise(
                ConversationCompactionError.InvalidConfiguration(
                    "Conversation compaction is unusable for session $sessionId: ${resolved.reason}"
                )
            )
        }

        // A session without any message has no thread to summarize.
        val leafMessageId = prepared.session.currentLeafMessageId
            ?: return@either ManualCompactionOutcome.Skipped(CompactionSkipReason.NOTHING_TO_COMPACT)

        val toolCalls = conversationTurnPersistence.loadSessionToolCalls(sessionId)
        val context = runCatching {
            chatContextBuilder.buildContext(
                startingMessageId = leafMessageId,
                sessionMessages = prepared.session.messages,
                toolCalls = toolCalls
            )
        }.getOrElse { failure ->
            // A broken chain cannot produce a trustworthy coverage, so the request fails instead of
            // summarizing a partial thread. Only the builder's own integrity failures are mapped; any
            // other throwable (including cancellation) keeps propagating.
            if (failure is IllegalStateException) {
                raise(
                    ConversationCompactionError.SourceChanged(
                        failure.message ?: "The displayed thread could not be reconstructed for session $sessionId"
                    )
                )
            }
            throw failure
        }

        if (context.units.isEmpty()) {
            return@either ManualCompactionOutcome.Skipped(CompactionSkipReason.NOTHING_TO_COMPACT)
        }

        // The largest eligible chunk supersedes every smaller eligible chunk, so it alone decides the
        // covered prefix, its summary and the identity ledger.
        val largestEligible = findLargestEligibleChunk(chunkDao.getChunksBySessionId(sessionId), context)
        val coveredCount = largestEligible?.coverageCount ?: 0
        if (largestEligible != null && coveredCount >= context.units.size) {
            return@either ManualCompactionOutcome.Skipped(CompactionSkipReason.ALREADY_COMPACTED)
        }

        // The window is `[summary] + uncovered tail`; without an eligible chunk it is the whole thread.
        val summary = largestEligible?.let { settings.summaryLabel + it.summary }
        val coveredSnapshots = largestEligible?.coverage
            .orEmpty()
            .map { covered -> SourceMessageSnapshot(id = covered.messageId, updatedAt = covered.observedUpdatedAt) }

        // The shared one-shot compaction owns the persistence decision and reports the outcome: a
        // verified chunk, or a skip when the produced summary did not shrink the window it replaces.
        val outcome = compactionService.compactNow(
            userId = userId,
            sessionId = sessionId,
            settings = settings,
            primaryConfig = prepared.llmConfig,
            summary = summary,
            units = context.units.drop(coveredCount),
            coveredSnapshots = coveredSnapshots,
            expectedLeafMessageId = leafMessageId
        ).bind()

        outcome
    }

    /**
     * Describes a failed runtime resolution as an unusable compaction configuration.
     *
     * A manual compaction is not a turn, so its preparation failures carry no request-shape meaning;
     * they all mean "this session cannot compact right now" and are reported with the reason that
     * caused them.
     *
     * @receiver The preparation failure to describe.
     * @param sessionId Session whose runtime could not be resolved.
     * @return Human-readable reason for the public error surface.
     */
    private fun ValidateNewMessageError.toCompactionReason(sessionId: Long): String = when (this) {
        is ValidateNewMessageError.SessionNotFound -> "Session $sessionId no longer exists"
        is ValidateNewMessageError.ParentNotInSession ->
            "Parent message $parentId is not part of session $sessionId"

        is ValidateNewMessageError.ModelConfigurationError ->
            "Session $sessionId cannot compact: $message"
    }
}
