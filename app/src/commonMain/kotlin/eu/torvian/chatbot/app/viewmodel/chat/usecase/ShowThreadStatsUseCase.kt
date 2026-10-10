package eu.torvian.chatbot.app.viewmodel.chat.usecase

import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.repository.SessionRepository
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.app.viewmodel.chat.state.ChatAreaDialogState
import eu.torvian.chatbot.app.viewmodel.chat.state.ChatState
import eu.torvian.chatbot.common.models.api.core.ThreadStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Use case for the thread statistics dialog.
 *
 * Owns the whole lifecycle of the feature: it publishes the dialog, fetches the statistics for the
 * session that is active at that moment, and exposes the outcome as the single source of truth the
 * dialog renders. Nothing is cached, so every open measures the conversation as it is then.
 *
 * @property sessionRepository Source of the server-computed statistics.
 * @property state Shared chat state the dialog is published into.
 * @property coroutineScope Scope the fetch runs on, so closing the chat screen cancels it.
 */
class ShowThreadStatsUseCase(
    private val sessionRepository: SessionRepository,
    private val state: ChatState,
    private val coroutineScope: CoroutineScope
) {

    private val logger = kmpLogger<ShowThreadStatsUseCase>()

    private val _stats = MutableStateFlow<DataState<RepositoryError, ThreadStats>>(DataState.Loading)

    /** In-flight fetch, kept so a newer load can supersede a slower one that is still running. */
    private var loadJob: Job? = null

    /**
     * Opens the dialog for the currently active session and starts the fetch.
     *
     * Without an active session there is nothing to describe, so no dialog is published.
     */
    fun show() {
        val sessionId = state.activeSessionId.value
        if (sessionId == null) {
            logger.debug("No active session; skipping the thread stats dialog")
            return
        }

        // Reset before publishing: the fetch is asynchronous, so a leftover value from a previous open
        // would otherwise be rendered as if it described the thread the user just asked about.
        _stats.value = DataState.Loading
        state.setDialogState(
            ChatAreaDialogState.ThreadStatsDetails(
                stats = _stats.asStateFlow(),
                onReload = { load(sessionId) },
                onDismiss = { state.cancelDialog() }
            )
        )
        load(sessionId)
    }

    /**
     * Fetches the statistics of [sessionId] and publishes the outcome to the dialog.
     *
     * Failures are reported through the dialog's error state alone; a second, transient notification
     * would tell the user about the same failure twice.
     *
     * @param sessionId The session whose current thread is measured.
     */
    private fun load(sessionId: Long) {
        // A reopened dialog or a double tap starts a second fetch while the first one is still running;
        // cancelling it keeps the older response from overwriting the newer one out of order.
        loadJob?.cancel()
        loadJob = coroutineScope.launch {
            _stats.value = DataState.Loading
            _stats.value = sessionRepository.getThreadStats(sessionId).fold(
                ifLeft = { error ->
                    logger.error("Failed to load thread stats: $error")
                    DataState.Error(error)
                },
                ifRight = { DataState.Success(it) }
            )
        }
    }
}
