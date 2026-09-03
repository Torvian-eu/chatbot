package eu.torvian.chatbot.server.data.dao.exposed

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.server.data.dao.ConversationCompactionChunkDao
import eu.torvian.chatbot.server.data.dao.error.ConversationCompactionChunkDaoError
import eu.torvian.chatbot.server.data.tables.ChatMessageTable
import eu.torvian.chatbot.server.data.tables.ConversationCompactionChunkMessageTable
import eu.torvian.chatbot.server.data.tables.ConversationCompactionChunkTable
import eu.torvian.chatbot.server.service.core.chat.compaction.CompactedMessageCoverage
import eu.torvian.chatbot.server.service.core.chat.compaction.ConversationCompactionChunk
import eu.torvian.chatbot.server.service.core.chat.compaction.ConversationCompactionChunkCandidate
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant

/**
 * Exposed implementation of [ConversationCompactionChunkDao].
 *
 * Chunk reads use two bounded queries (chunks for the session, then coverage for those chunks)
 * grouped in memory to avoid an N+1 coverage lookup. Verified insertion performs the full source
 * verification inside the same transaction as the insert so a changed source snapshot or a partial
 * write can never produce an apparently usable chunk.
 *
 * @property transactionScope Transaction wrapper that keeps each DAO call atomic.
 */
class ConversationCompactionChunkDaoExposed(
    private val transactionScope: TransactionScope
) : ConversationCompactionChunkDao {

    override suspend fun getChunksBySessionId(sessionId: Long): List<ConversationCompactionChunk> =
        transactionScope.transaction {
            val chunkRows = ConversationCompactionChunkTable
                .selectAll()
                .where { ConversationCompactionChunkTable.sessionId eq sessionId }
                // Newest first with a deterministic id tie-break; eligibility re-sorts anyway, but a
                // stable read order keeps diagnostics and rolling-input behavior predictable.
                .orderBy(
                    ConversationCompactionChunkTable.createdAt to SortOrder.DESC,
                    ConversationCompactionChunkTable.id to SortOrder.DESC
                )
                .toList()
            if (chunkRows.isEmpty()) return@transaction emptyList()

            val chunkIds = chunkRows.map { it[ConversationCompactionChunkTable.id].value }
            val coverageRows = ConversationCompactionChunkMessageTable
                .selectAll()
                .where { ConversationCompactionChunkMessageTable.chunkId inList chunkIds }
                .toList()

            val coverageByChunk = coverageRows.groupBy { it[ConversationCompactionChunkMessageTable.chunkId] }
                .mapValues { (_, rows) ->
                    rows
                        .map { row ->
                            CompactedMessageCoverage(
                                ordinal = row[ConversationCompactionChunkMessageTable.ordinal],
                                messageId = row[ConversationCompactionChunkMessageTable.messageId],
                                observedUpdatedAt = Instant.fromEpochMilliseconds(
                                    row[ConversationCompactionChunkMessageTable.observedUpdatedAt]
                                )
                            )
                        }
                        .sortedBy { it.ordinal }
                }

            chunkRows.map { row -> row.toChunk(coverageByChunk[row[ConversationCompactionChunkTable.id]] ?: emptyList()) }
        }

    override suspend fun insertVerifiedChunk(
        candidate: ConversationCompactionChunkCandidate,
        expectedLeafMessageId: Long
    ): Either<ConversationCompactionChunkDaoError, ConversationCompactionChunk> =
        transactionScope.transaction {
            either {
                // The candidate coverage must be a complete ordered chain; defensive rejection of
                // malformed input keeps legacy or corrupted data out of the verified insert path.
                val coverage = candidate.coverage.sortedBy { it.ordinal }
                ensure(coverage.isNotEmpty()) {
                    ConversationCompactionChunkDaoError.SourceVerificationFailed(
                        "Cannot persist a compaction chunk with empty coverage"
                    )
                }
                ensure(coverage.map { it.ordinal } == coverage.indices.toList()) {
                    ConversationCompactionChunkDaoError.SourceVerificationFailed(
                        "Coverage ordinals must be contiguous starting at 0"
                    )
                }

                val messageIds = coverage.map { it.messageId }
                val currentRows = ChatMessageTable
                    .selectAll()
                    .where { ChatMessageTable.id inList messageIds }
                    .associateBy { it[ChatMessageTable.id].value }

                // All covered messages must still exist and belong to the candidate's session.
                ensure(currentRows.size == messageIds.size) {
                    ConversationCompactionChunkDaoError.SourceVerificationFailed(
                        "Covered message set changed: expected ${messageIds.size} rows, found ${currentRows.size}"
                    )
                }

                // Exact timestamp equality implements the edit-invalidation rule at the persistence
                // boundary; a changed message invalidates the whole insert.
                coverage.forEach { covered ->
                    val row = currentRows.getValue(covered.messageId)
                    ensure(row[ChatMessageTable.sessionId].value == candidate.sessionId) {
                        ConversationCompactionChunkDaoError.SourceVerificationFailed(
                            "Covered message ${covered.messageId} belongs to session " +
                                "${row[ChatMessageTable.sessionId].value}, expected ${candidate.sessionId}"
                        )
                    }
                    ensure(
                        Instant.fromEpochMilliseconds(row[ChatMessageTable.updatedAt]) ==
                            covered.observedUpdatedAt
                    ) {
                        ConversationCompactionChunkDaoError.SourceVerificationFailed(
                            "Covered message ${covered.messageId} was modified after the summary was created"
                        )
                    }
                }

                // The ordered parent chain must run from the root to the expected leaf; this also
                // rejects mixed-session/broken coverage before any row is written.
                var previousId: Long? = null
                coverage.forEach { covered ->
                    val row = currentRows.getValue(covered.messageId)
                    val parentId = row[ChatMessageTable.parentMessageId]?.value
                    when {
                        previousId == null && parentId != null -> {
                            raise(
                                ConversationCompactionChunkDaoError.SourceVerificationFailed(
                                    "Coverage chain does not start at a root message: ${covered.messageId}"
                                )
                            )
                        }

                        previousId != null && parentId != previousId -> {
                            raise(
                                ConversationCompactionChunkDaoError.SourceVerificationFailed(
                                    "Parent chain broken at message ${covered.messageId}: " +
                                        "expected parent $previousId, found $parentId"
                                )
                            )
                        }

                        else -> previousId = covered.messageId
                    }
                }
                ensure(previousId == expectedLeafMessageId) {
                    ConversationCompactionChunkDaoError.SourceVerificationFailed(
                        "Coverage leaf $previousId does not match the expected leaf $expectedLeafMessageId"
                    )
                }

                val persistedChunk = try {
                    val chunkId = ConversationCompactionChunkTable.insert {
                        it[sessionId] = candidate.sessionId
                        it[summary] = candidate.summary
                        it[modelId] = candidate.modelId
                        it[settingsId] = candidate.settingsId
                        it[providerId] = candidate.providerId
                        it[modelName] = candidate.modelName
                        it[settingsName] = candidate.settingsName
                        it[providerName] = candidate.providerName
                        it[instruction] = candidate.instruction
                        it[thresholdTokens] = candidate.thresholdTokens
                        it[sourceTokenCount] = candidate.sourceTokenCount
                        it[resultTokenCount] = candidate.resultTokenCount
                        it[tokenCounterVersion] = candidate.tokenCounterVersion
                        it[coverageCount] = candidate.coverageCount
                        it[createdAt] = candidate.createdAt
                    }[ConversationCompactionChunkTable.id].value

                    coverage.forEach { covered ->
                        ConversationCompactionChunkMessageTable.insert {
                            it[ConversationCompactionChunkMessageTable.chunkId] = chunkId
                            it[ordinal] = covered.ordinal
                            it[messageId] = covered.messageId
                            it[observedUpdatedAt] = covered.observedUpdatedAt.toEpochMilliseconds()
                        }
                    }

                    // Verify the coverage rows really landed inside the same transaction; a count
                    // mismatch means the write was partial and must be rolled back with the chunk.
                    val persistedCoverageCount = ConversationCompactionChunkMessageTable
                        .selectAll()
                        .where { ConversationCompactionChunkMessageTable.chunkId eq chunkId }
                        .count()
                    ensure(persistedCoverageCount.toInt() == candidate.coverageCount) {
                        ConversationCompactionChunkDaoError.PersistenceFailed(
                            "Persisted coverage count $persistedCoverageCount does not match " +
                                "expected ${candidate.coverageCount}",
                            null
                        )
                    }

                    ConversationCompactionChunk(
                        id = chunkId,
                        sessionId = candidate.sessionId,
                        summary = candidate.summary,
                        modelId = candidate.modelId,
                        settingsId = candidate.settingsId,
                        providerId = candidate.providerId,
                        modelName = candidate.modelName,
                        settingsName = candidate.settingsName,
                        providerName = candidate.providerName,
                        instruction = candidate.instruction,
                        thresholdTokens = candidate.thresholdTokens,
                        sourceTokenCount = candidate.sourceTokenCount,
                        resultTokenCount = candidate.resultTokenCount,
                        tokenCounterVersion = candidate.tokenCounterVersion,
                        coverageCount = candidate.coverageCount,
                        createdAt = candidate.createdAt,
                        coverage = coverage
                    )
                } catch (e: Exception) {
                    // Cancellation must propagate (external coroutine cancellation is not a persistence
                    // failure); everything else is a terminal persistence error that rolls back the insert.
                    if (e is CancellationException) throw e
                    raise(
                        ConversationCompactionChunkDaoError.PersistenceFailed(
                            "Failed to persist compaction chunk for session ${candidate.sessionId}: ${e.message}",
                            e
                        )
                    )
                }

                // The either block's success value is the persisted chunk.
                persistedChunk
            }
        }

    /**
     * Maps a chunk row plus its coverage into the domain record.
     *
     * @receiver The chunk [ResultRow].
     * @param coverage Ordered coverage rows belonging to this chunk.
     * @return The domain [ConversationCompactionChunk].
     */
    private fun ResultRow.toChunk(coverage: List<CompactedMessageCoverage>): ConversationCompactionChunk =
        ConversationCompactionChunk(
            id = this[ConversationCompactionChunkTable.id].value,
            sessionId = this[ConversationCompactionChunkTable.sessionId].value,
            summary = this[ConversationCompactionChunkTable.summary],
            modelId = this[ConversationCompactionChunkTable.modelId]?.value,
            settingsId = this[ConversationCompactionChunkTable.settingsId]?.value,
            providerId = this[ConversationCompactionChunkTable.providerId]?.value,
            modelName = this[ConversationCompactionChunkTable.modelName],
            settingsName = this[ConversationCompactionChunkTable.settingsName],
            providerName = this[ConversationCompactionChunkTable.providerName],
            instruction = this[ConversationCompactionChunkTable.instruction],
            thresholdTokens = this[ConversationCompactionChunkTable.thresholdTokens],
            sourceTokenCount = this[ConversationCompactionChunkTable.sourceTokenCount],
            resultTokenCount = this[ConversationCompactionChunkTable.resultTokenCount],
            tokenCounterVersion = this[ConversationCompactionChunkTable.tokenCounterVersion],
            coverageCount = this[ConversationCompactionChunkTable.coverageCount],
            createdAt = this[ConversationCompactionChunkTable.createdAt],
            coverage = coverage
        )
}
