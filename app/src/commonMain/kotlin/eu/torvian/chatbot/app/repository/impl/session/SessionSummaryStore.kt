package eu.torvian.chatbot.app.repository.impl.session

import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.common.models.core.ChatSessionSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.Instant

/**
 * Owns the cached session summaries the session list is derived from, and the ordering instant they carry.
 *
 * The cached list is the client's single source for the panel order, so every write goes through this type: a load
 * replaces the list, a local create or delete inserts or removes a row, a metadata mutation copies one field, and
 * turn activity raises a row's ordering instant. Two rules protect that order. A write that is not a load or a
 * failure never leaves [DataState.Success], so the panel keeps its composition, its scroll position, its collapsed
 * sections and its open dialogs. The ordering instant never moves backwards, neither on the activity path nor when
 * a server snapshot is applied, so a freshly active session stays at the top of its section and a snapshot taken
 * before a local turn cannot revert the optimistic order.
 */
internal class SessionSummaryStore {

    /** The cached list, exposed as the read-only list contract of the session API. */
    private val _sessions =
        MutableStateFlow<DataState<RepositoryError, List<ChatSessionSummary>>>(DataState.Idle)

    /** The cached list every reader observes. */
    val sessions: StateFlow<DataState<RepositoryError, List<ChatSessionSummary>>> = _sessions.asStateFlow()

    /**
     * Moves the list into [DataState.Loading] unless a load is already in flight.
     *
     * The guard belongs here because the state it inspects does: a caller that skipped it would start a second,
     * concurrent full load over the same rows.
     *
     * @return `true` when this call started the load, `false` when the list was already loading.
     */
    fun beginLoading(): Boolean {
        if (_sessions.value.isLoading) return false
        _sessions.value = DataState.Loading
        return true
    }

    /** Replaces the list with the server's authoritative entries; used by the full load only. */
    fun replaceAll(summaries: List<ChatSessionSummary>) {
        _sessions.update { DataState.Success(summaries) }
    }

    /**
     * Moves the list into [DataState.Error], so a failed full load is visible.
     *
     * @param error The failure to report.
     */
    fun markError(error: RepositoryError) {
        _sessions.update { DataState.Error(error) }
    }

    /**
     * Appends a locally created or cloned summary to the end of the list.
     *
     * @param summary The new session's summary.
     */
    fun append(summary: ChatSessionSummary) {
        updateRows { rows -> rows + summary }
    }

    /**
     * Drops a locally deleted session from the list.
     *
     * @param sessionId The deleted session.
     */
    fun remove(sessionId: Long) {
        updateRows { rows -> rows.filter { it.id != sessionId } }
    }

    /**
     * Copies the fields [update] changes into one row, leaving every other row and the ordering instant untouched.
     *
     * @param sessionId The session whose row is updated.
     * @param update The field copy to apply to that row.
     */
    fun mutate(sessionId: Long, update: (ChatSessionSummary) -> ChatSessionSummary) {
        updateRows { rows -> rows.map { row -> if (row.id == sessionId) update(row) else row } }
    }

    /**
     * Raises one row's ordering instant to [at], never lowering it.
     *
     * Equal instants leave the row as it is, which keeps the relative order of sessions updated in the same instant.
     *
     * @param sessionId The session the activity belongs to.
     * @param at Server-assigned instant of the persisted message that carried the activity.
     */
    fun raiseActivity(sessionId: Long, at: Instant) {
        mutate(sessionId) { row -> if (at > row.updatedAt) row.copy(updatedAt = at) else row }
    }

    /**
     * Adopts the server's fields for the rows the client already holds, keeping each row's ordering instant at
     * least the locally known one.
     *
     * Only ids the client holds are replaced, which keeps the row set the product of the client's own create and
     * delete operations, and a row the server does not know is left as it is. A snapshot entry the client does not
     * hold is ignored for the same reason.
     *
     * @param serverSummaries The entries read from the server.
     */
    fun mergeAuthoritative(serverSummaries: List<ChatSessionSummary>) {
        val serverById = serverSummaries.associateBy { it.id }
        updateRows { rows ->
            rows.map { localRow ->
                val serverRow = serverById[localRow.id]
                // Keeping the newer instant makes a snapshot taken before a locally applied turn harmless.
                serverRow?.copy(updatedAt = maxOf(serverRow.updatedAt, localRow.updatedAt)) ?: localRow
            }
        }
    }

    /**
     * Applies [transform] to the row list while it is [DataState.Success].
     *
     * Any other state holds no rows to transform and is kept, so a row write can never resurrect a failed or
     * still-running load.
     *
     * @param transform The row transformation to apply.
     */
    private fun updateRows(transform: (List<ChatSessionSummary>) -> List<ChatSessionSummary>) {
        _sessions.update { currentState ->
            if (currentState is DataState.Success) DataState.Success(transform(currentState.data)) else currentState
        }
    }
}
