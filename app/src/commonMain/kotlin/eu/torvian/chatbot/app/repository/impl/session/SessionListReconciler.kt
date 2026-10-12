package eu.torvian.chatbot.app.repository.impl.session

import eu.torvian.chatbot.app.service.api.SessionApi
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Re-reads the session list from the server and merges the snapshot into [store] after a turn ended.
 *
 * It exists so a finished turn can align the panel order with the server without the turn code owning the list, and
 * so the re-read never delays the turn: [schedule] runs it in [scope]. A failed read is reported at debug level and
 * changes nothing, because ordering is cosmetic.
 *
 * @property sessionApi The API client the authoritative list is read from.
 * @property store The cached list the snapshot is merged into.
 * @property scope Scope hosting the re-read. The reconciler never cancels it, and a supplied scope must outlive the
 *   reconciler because every completed turn launches one re-read into it.
 */
internal class SessionListReconciler(
    private val sessionApi: SessionApi,
    private val store: SessionSummaryStore,
    private val scope: CoroutineScope
) {

    // Two turn endings must not apply their snapshots in an order other than the one the snapshots were taken in.
    private val mutex = Mutex()

    /** Runs one re-read in [scope], never in the caller's coroutine. */
    fun schedule() {
        scope.launch { refresh() }
    }

    /**
     * Reads the authoritative list and merges it into the store, keeping the current order when the read fails.
     *
     * Serialized against every other re-read, so the snapshots are applied in the order they were taken.
     */
    suspend fun refresh() {
        mutex.withLock {
            sessionApi.getAllSessions()
                .map { serverSummaries -> store.mergeAuthoritative(serverSummaries) }
                .mapLeft { error ->
                    logger.debug("Session list reconciliation failed, keeping the current order: $error")
                }
        }
    }

    companion object {
        private val logger = kmpLogger<SessionListReconciler>()
    }
}
