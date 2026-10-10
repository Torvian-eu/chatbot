package eu.torvian.chatbot.app.viewmodel.chat

import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.app.viewmodel.SearchNavigationIntent
import eu.torvian.chatbot.app.viewmodel.SearchNavigationState
import eu.torvian.chatbot.app.viewmodel.chat.state.ChatState
import eu.torvian.chatbot.app.viewmodel.chat.usecase.SwitchBranchUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*

/**
 * Applies cross-session search navigation intents to the shared chat state.
 *
 * Watches [SearchNavigationState.intent] and, once this session's data is loaded, switches the
 * displayed branch when needed and activates the in-session search for the target message.
 *
 * @param navigationState Holder of the pending cross-session navigation intent.
 * @param state The shared chat state the intent is applied to.
 * @param switchBranchUC Use case that switches the displayed branch when the target is not visible.
 * @param backgroundScope Scope that owns the intent observer for the view model's lifetime.
 * @param isCompactionInProgress Guard consulted to avoid switching the branch while a compaction runs.
 */
class SearchNavigationObserver(
    private val navigationState: SearchNavigationState,
    private val state: ChatState,
    private val switchBranchUC: SwitchBranchUseCase,
    private val backgroundScope: CoroutineScope,
    private val isCompactionInProgress: () -> Boolean
) {

    private val logger = kmpLogger<SearchNavigationObserver>()

    /**
     * Starts observing cross-session navigation intent for the lifetime of [backgroundScope].
     * Call once, after construction.
     */
    fun start() {
        // Observe navigation intent and react when this VM is active for the target session
        // and the session data is loaded. This handles all cases:
        // - Intent arrives before session load completes
        // - Session load completes after intent is already present
        // - Session becomes active later
        combine(
            navigationState.intent,
            state.activeSessionId,
            state.sessionDataState
        ) { intent, activeId, sessionData ->
            Triple(intent, activeId, sessionData)
        }
            .filter { (intent, activeId, sessionData) ->
                intent != null && activeId == intent.sessionId && sessionData is DataState.Success
            }
            .onEach { (intent, _, _) ->
                intent?.let { safeIntent ->
                    processNavigationIntent(safeIntent)
                }
            }
            .launchIn(backgroundScope)
    }

    /**
     * Processes a navigation intent sequentially, awaiting branch switch before search activation.
     *
     * @param intent The navigation intent to process.
     */
    private suspend fun processNavigationIntent(intent: SearchNavigationIntent) {
        // Get current leaf before any potential branch switch
        val currentLeafBeforeSwitch = (state.sessionDataState.value as? DataState.Success)?.data?.currentLeafMessageId

        // Check if target message is already visible in current branch
        val isMessageVisible = state.displayedMessages.value.any { it.id == intent.messageId }

        if (isMessageVisible) {
            // No branch switch needed, no rollback target
            state.setRollbackTarget(null)
        } else if (!isCompactionInProgress()) {
            // Branch switch needed - capture current leaf as rollback target
            state.setRollbackTarget(currentLeafBeforeSwitch)
            switchBranchUC.execute(intent.messageId)
        }

        // Set the pending target and query - the search result derivation flow will
        // consume the pending target and select the index when results are computed
        state.setPendingSearchMessageTarget(intent.messageId)
        state.updateSearchQuery(intent.query)
        state.showSearch()

        // Clear the intent so it's not reprocessed
        navigationState.clearIntent()

        logger.debug("Processed navigation intent: session ${intent.sessionId}, message ${intent.messageId}")
    }
}
