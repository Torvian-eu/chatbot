package eu.torvian.chatbot.server.service.core.chat.compaction

import arrow.core.Either

/**
 * Runs a user-requested (forced) conversation compaction of a session's displayed thread.
 *
 * The operation is a one-shot auxiliary summarization performed outside any assistant turn: it
 * targets the branch ending at the session's current leaf message, persists one verified chunk, and
 * writes nothing to the transcript. The input composition follows the rolling-window rule — an
 * eligible prefix chunk's summary plus the uncovered tail, or the whole displayed thread when no
 * chunk is eligible.
 */
interface ConversationManualCompactionService {

    /**
     * Compacts the session's displayed thread immediately, below the normal threshold.
     *
     * The caller guarantees session access for [userId] before invoking this operation. The session's
     * runtime inputs are resolved exactly as a turn would resolve them, so the request fails with an
     * [ConversationCompactionError.InvalidConfiguration] when the session cannot be prepared or when
     * compaction is effectively disabled for it. A thread whose whole coverage is already held by the
     * largest eligible chunk is a no-op, as is a session without any message, and so is a summary that
     * does not shrink the content it replaces.
     *
     * @param userId Authenticated user requesting the compaction; owner of the preference.
     * @param sessionId Session whose displayed thread is compacted.
     * @return Either a [ConversationCompactionError] or the outcome of the request.
     */
    suspend fun compactThread(
        userId: Long,
        sessionId: Long
    ): Either<ConversationCompactionError, ManualCompactionOutcome>
}
