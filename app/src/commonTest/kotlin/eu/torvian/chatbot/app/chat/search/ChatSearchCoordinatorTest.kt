package eu.torvian.chatbot.app.chat.search

import eu.torvian.chatbot.app.testutils.data.assistantMessage
import eu.torvian.chatbot.app.testutils.data.instant
import eu.torvian.chatbot.app.testutils.data.userMessage
import eu.torvian.chatbot.common.models.api.core.MessageSearchResult
import eu.torvian.chatbot.common.models.core.ChatMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies the extracted chat-search coordinator keeps UI state and navigation orchestration out of the composable.
 */
class ChatSearchCoordinatorTest {
    /**
     * Ensures query updates derive matches from the latest displayed messages and normalize selection.
     */
    @Test
    fun updateSearchQuery_derivesMatchesFromDisplayedMessages() {
        val coordinator = ChatSearchCoordinator()
        val displayedMessages = listOf(
            userMessage(id = 1, sessionId = 7, content = "alpha beta"),
            assistantMessage(id = 2, sessionId = 7, content = "Alpha again"),
        )

        coordinator.onChatContextChanged(
            isUserAuthenticated = true,
            selectedSessionId = 7L,
            activeChatSessionId = 7L,
            isSessionLoaded = true,
            currentLeafMessageId = 2L,
            displayedMessages = displayedMessages,
            onSwitchBranchToMessage = {},
        )

        coordinator.showSearch()
        coordinator.updateSearchQuery("alpha")

        val state = coordinator.uiState.value
        assertTrue(state.isSearchActive)
        assertEquals("alpha", state.searchQuery)
        assertEquals(2, state.searchResults.size)
        assertEquals(0, state.currentSearchIndex)
        assertEquals(1L, state.searchResults[0].messageId)
        assertEquals(2L, state.searchResults[1].messageId)
    }

    /**
     * Confirms navigation continues to wrap through the derived search results.
     */
    @Test
    fun navigateSearchResult_wrapsAcrossOccurrences() {
        val coordinator = ChatSearchCoordinator()

        coordinator.onChatContextChanged(
            isUserAuthenticated = true,
            selectedSessionId = 9L,
            activeChatSessionId = 9L,
            isSessionLoaded = true,
            currentLeafMessageId = 1L,
            displayedMessages = listOf(
                userMessage(id = 1, sessionId = 9, content = "alpha alpha"),
            ),
            onSwitchBranchToMessage = {},
        )

        coordinator.showSearch()
        coordinator.updateSearchQuery("alpha")
        coordinator.navigateSearchResult(SearchDirection.FORWARD)
        assertEquals(1, coordinator.uiState.value.currentSearchIndex)

        coordinator.navigateSearchResult(SearchDirection.FORWARD)
        assertEquals(0, coordinator.uiState.value.currentSearchIndex)

        coordinator.navigateSearchResult(SearchDirection.BACKWARD)
        assertEquals(1, coordinator.uiState.value.currentSearchIndex)
    }

    /**
     * Ensures an off-branch search hit captures rollback state before requesting the branch switch.
     */
    @Test
    fun onChatContextChanged_requestsBranchSwitchAndCapturesRollbackForOffBranchTarget() {
        val coordinator = ChatSearchCoordinator()
        val requestedBranchSwitches = mutableListOf<Long>()

        coordinator.onChatContextChanged(
            isUserAuthenticated = true,
            selectedSessionId = 7L,
            activeChatSessionId = 7L,
            isSessionLoaded = true,
            currentLeafMessageId = 6L,
            displayedMessages = listOf(
                userMessage(id = 5, sessionId = 7, content = "previous root", childrenMessageIds = listOf(6L)),
                assistantMessage(id = 6, sessionId = 7, content = "previous leaf", parentMessageId = 5L),
            ),
            onSwitchBranchToMessage = {},
        )

        coordinator.beginCrossSessionNavigation(
            result = searchResult(sessionId = 11, messageId = 42),
            query = "target",
        )

        coordinator.onChatContextChanged(
            isUserAuthenticated = true,
            selectedSessionId = 11L,
            activeChatSessionId = 11L,
            isSessionLoaded = true,
            currentLeafMessageId = 1L,
            displayedMessages = listOf(userMessage(id = 1, sessionId = 11, content = "other")),
            onSwitchBranchToMessage = requestedBranchSwitches::add,
        )

        val rollbackTarget = coordinator.uiState.value.rollbackTarget
        assertEquals(listOf(42L), requestedBranchSwitches)
        assertNotNull(rollbackTarget)
        assertEquals(7L, rollbackTarget.sessionId)
        assertEquals(6L, rollbackTarget.leafMessageId)
        assertEquals(11L, coordinator.uiState.value.rollbackContextSessionId)
        assertFalse(coordinator.uiState.value.canReturnToPreviousThread)
        assertFalse(coordinator.uiState.value.isSearchActive)
        assertEquals("", coordinator.uiState.value.searchQuery)
    }

    /**
     * Verifies rollback visibility is scoped to the active search session instead of any stored target.
     */
    @Test
    fun canReturnToPreviousThread_requiresActiveSearchInMatchingRollbackSession() {
        val coordinator = ChatSearchCoordinator()

        coordinator.onChatContextChanged(
            isUserAuthenticated = true,
            selectedSessionId = 7L,
            activeChatSessionId = 7L,
            isSessionLoaded = true,
            currentLeafMessageId = 6L,
            displayedMessages = listOf(
                userMessage(id = 5, sessionId = 7, content = "previous root", childrenMessageIds = listOf(6L)),
                assistantMessage(id = 6, sessionId = 7, content = "previous leaf", parentMessageId = 5L),
            ),
            onSwitchBranchToMessage = {},
        )
        coordinator.beginCrossSessionNavigation(
            result = searchResult(sessionId = 11, messageId = 42),
            query = "target",
        )
        coordinator.onChatContextChanged(
            isUserAuthenticated = true,
            selectedSessionId = 11L,
            activeChatSessionId = 11L,
            isSessionLoaded = true,
            currentLeafMessageId = 1L,
            displayedMessages = listOf(userMessage(id = 1, sessionId = 11, content = "other")),
            onSwitchBranchToMessage = {},
        )
        coordinator.onChatContextChanged(
            isUserAuthenticated = true,
            selectedSessionId = 11L,
            activeChatSessionId = 11L,
            isSessionLoaded = true,
            currentLeafMessageId = 42L,
            displayedMessages = listOf(userMessage(id = 42, sessionId = 11, content = "target")),
            onSwitchBranchToMessage = {},
        )

        val postNavigationState = coordinator.uiState.value
        assertTrue(postNavigationState.isSearchActive)
        assertTrue(postNavigationState.canReturnToPreviousThread)
        assertEquals(11L, postNavigationState.rollbackContextSessionId)

        coordinator.onChatContextChanged(
            isUserAuthenticated = true,
            selectedSessionId = 31L,
            activeChatSessionId = 31L,
            isSessionLoaded = true,
            currentLeafMessageId = 2L,
            displayedMessages = listOf(userMessage(id = 2, sessionId = 31, content = "unrelated")),
            onSwitchBranchToMessage = {},
        )

        val unrelatedSessionState = coordinator.uiState.value
        assertNotNull(unrelatedSessionState.rollbackTarget)
        assertEquals(11L, unrelatedSessionState.rollbackContextSessionId)
        assertFalse(unrelatedSessionState.canReturnToPreviousThread)

        coordinator.showSearch()

        assertTrue(coordinator.uiState.value.isSearchActive)
        assertFalse(coordinator.uiState.value.canReturnToPreviousThread)
    }

    /**
     * Verifies a visible cross-session hit activates in-session search and jumps to the target message.
     */
    @Test
    fun onChatContextChanged_activatesSearchAndJumpsToVisibleTarget() {
        val coordinator = ChatSearchCoordinator()
        val displayedMessages = listOf<ChatMessage>(
            userMessage(id = 10, sessionId = 15, content = "first target"),
            assistantMessage(id = 11, sessionId = 15, content = "second"),
        )

        coordinator.beginCrossSessionNavigation(
            result = searchResult(sessionId = 15, messageId = 10),
            query = "target",
        )

        coordinator.onChatContextChanged(
            isUserAuthenticated = true,
            selectedSessionId = 15L,
            activeChatSessionId = 15L,
            isSessionLoaded = true,
            currentLeafMessageId = 11L,
            displayedMessages = displayedMessages,
            onSwitchBranchToMessage = {},
        )

        val state = coordinator.uiState.value
        assertTrue(state.isSearchActive)
        assertEquals("target", state.searchQuery)
        assertEquals(1, state.searchResults.size)
        assertEquals(0, state.currentSearchIndex)
        assertEquals(10L, state.searchResults[state.currentSearchIndex].messageId)
        assertNull(state.rollbackTarget)
        assertFalse(state.canReturnToPreviousThread)
    }

    /**
     * Ensures every staged coordinator action advances the same reconciliation trigger observed by the UI.
     */
    @Test
    fun stagedNavigationAndRollback_bothAdvanceReconciliationVersion() {
        val coordinator = ChatSearchCoordinator()

        assertEquals(0L, coordinator.reconciliationVersion.value)

        coordinator.beginCrossSessionNavigation(
            result = searchResult(sessionId = 15, messageId = 10),
            query = "target",
        )

        assertEquals(1L, coordinator.reconciliationVersion.value)

        coordinator.onChatContextChanged(
            isUserAuthenticated = true,
            selectedSessionId = 7L,
            activeChatSessionId = 7L,
            isSessionLoaded = true,
            currentLeafMessageId = 6L,
            displayedMessages = listOf(
                userMessage(id = 5, sessionId = 7, content = "previous root", childrenMessageIds = listOf(6L)),
                assistantMessage(id = 6, sessionId = 7, content = "previous leaf", parentMessageId = 5L),
            ),
            onSwitchBranchToMessage = {},
        )
        coordinator.beginCrossSessionNavigation(
            result = searchResult(sessionId = 11, messageId = 42),
            query = "target",
        )
        coordinator.onChatContextChanged(
            isUserAuthenticated = true,
            selectedSessionId = 11L,
            activeChatSessionId = 11L,
            isSessionLoaded = true,
            currentLeafMessageId = 1L,
            displayedMessages = listOf(userMessage(id = 1, sessionId = 11, content = "other")),
            onSwitchBranchToMessage = {},
        )
        coordinator.onChatContextChanged(
            isUserAuthenticated = true,
            selectedSessionId = 11L,
            activeChatSessionId = 11L,
            isSessionLoaded = true,
            currentLeafMessageId = 42L,
            displayedMessages = listOf(userMessage(id = 42, sessionId = 11, content = "target")),
            onSwitchBranchToMessage = {},
        )

        coordinator.returnToPreviousThread {}

        assertEquals(3L, coordinator.reconciliationVersion.value)
    }

    /**
     * Verifies rollback reopens the previous session, restores the remembered leaf branch, and then clears itself.
     */
    @Test
    fun returnToPreviousThread_restoresPreviousLeafAndClearsRollbackState() {
        val coordinator = ChatSearchCoordinator()
        val requestedBranchSwitches = mutableListOf<Long>()
        val requestedSessions = mutableListOf<Long>()
        val previousThread = listOf(
            userMessage(id = 5, sessionId = 7, content = "previous root", childrenMessageIds = listOf(6L, 8L)),
            assistantMessage(id = 6, sessionId = 7, content = "previous leaf", parentMessageId = 5L),
        )

        coordinator.onChatContextChanged(
            isUserAuthenticated = true,
            selectedSessionId = 7L,
            activeChatSessionId = 7L,
            isSessionLoaded = true,
            currentLeafMessageId = 6L,
            displayedMessages = previousThread,
            onSwitchBranchToMessage = requestedBranchSwitches::add,
        )
        coordinator.beginCrossSessionNavigation(
            result = searchResult(sessionId = 11, messageId = 42),
            query = "target",
        )
        coordinator.onChatContextChanged(
            isUserAuthenticated = true,
            selectedSessionId = 11L,
            activeChatSessionId = 11L,
            isSessionLoaded = true,
            currentLeafMessageId = 1L,
            displayedMessages = listOf(userMessage(id = 1, sessionId = 11, content = "other")),
            onSwitchBranchToMessage = requestedBranchSwitches::add,
        )
        coordinator.onChatContextChanged(
            isUserAuthenticated = true,
            selectedSessionId = 11L,
            activeChatSessionId = 11L,
            isSessionLoaded = true,
            currentLeafMessageId = 42L,
            displayedMessages = listOf(userMessage(id = 42, sessionId = 11, content = "target")),
            onSwitchBranchToMessage = requestedBranchSwitches::add,
        )

        assertTrue(coordinator.uiState.value.canReturnToPreviousThread)
        assertEquals(11L, coordinator.uiState.value.rollbackContextSessionId)

        coordinator.returnToPreviousThread(requestedSessions::add)
        coordinator.onChatContextChanged(
            isUserAuthenticated = true,
            selectedSessionId = 7L,
            activeChatSessionId = 7L,
            isSessionLoaded = true,
            currentLeafMessageId = 8L,
            displayedMessages = listOf(
                userMessage(id = 5, sessionId = 7, content = "previous root", childrenMessageIds = listOf(6L, 8L)),
                assistantMessage(id = 8, sessionId = 7, content = "other branch", parentMessageId = 5L),
            ),
            onSwitchBranchToMessage = requestedBranchSwitches::add,
        )
        coordinator.onChatContextChanged(
            isUserAuthenticated = true,
            selectedSessionId = 7L,
            activeChatSessionId = 7L,
            isSessionLoaded = true,
            currentLeafMessageId = 6L,
            displayedMessages = previousThread,
            onSwitchBranchToMessage = requestedBranchSwitches::add,
        )

        assertEquals(listOf(7L), requestedSessions)
        assertEquals(listOf(42L, 6L), requestedBranchSwitches)
        assertNull(coordinator.uiState.value.rollbackTarget)
        assertNull(coordinator.uiState.value.rollbackContextSessionId)
        assertFalse(coordinator.uiState.value.canReturnToPreviousThread)
    }

    /**
     * Verifies rollback can restore a previous thread immediately when the original session is still active.
     */
    @Test
    fun returnToPreviousThread_sameSessionStagesImmediateRollbackReconciliation() {
        val coordinator = ChatSearchCoordinator()
        val requestedBranchSwitches = mutableListOf<Long>()
        val requestedSessions = mutableListOf<Long>()
        val previousThread = listOf(
            userMessage(id = 5, sessionId = 7, content = "previous root", childrenMessageIds = listOf(6L, 8L)),
            assistantMessage(id = 6, sessionId = 7, content = "previous leaf", parentMessageId = 5L),
        )

        coordinator.onChatContextChanged(
            isUserAuthenticated = true,
            selectedSessionId = 7L,
            activeChatSessionId = 7L,
            isSessionLoaded = true,
            currentLeafMessageId = 6L,
            displayedMessages = previousThread,
            onSwitchBranchToMessage = requestedBranchSwitches::add,
        )
        coordinator.beginCrossSessionNavigation(
            result = searchResult(sessionId = 7, messageId = 42),
            query = "target",
        )
        coordinator.onChatContextChanged(
            isUserAuthenticated = true,
            selectedSessionId = 7L,
            activeChatSessionId = 7L,
            isSessionLoaded = true,
            currentLeafMessageId = 8L,
            displayedMessages = listOf(
                userMessage(id = 5, sessionId = 7, content = "previous root", childrenMessageIds = listOf(6L, 8L)),
                assistantMessage(id = 8, sessionId = 7, content = "other branch", parentMessageId = 5L),
            ),
            onSwitchBranchToMessage = requestedBranchSwitches::add,
        )
        coordinator.onChatContextChanged(
            isUserAuthenticated = true,
            selectedSessionId = 7L,
            activeChatSessionId = 7L,
            isSessionLoaded = true,
            currentLeafMessageId = 42L,
            displayedMessages = listOf(userMessage(id = 42, sessionId = 7, content = "target")),
            onSwitchBranchToMessage = requestedBranchSwitches::add,
        )

        assertTrue(coordinator.uiState.value.canReturnToPreviousThread)
        assertEquals(7L, coordinator.uiState.value.rollbackContextSessionId)

        coordinator.returnToPreviousThread(requestedSessions::add)
        coordinator.onChatContextChanged(
            isUserAuthenticated = true,
            selectedSessionId = 7L,
            activeChatSessionId = 7L,
            isSessionLoaded = true,
            currentLeafMessageId = 42L,
            displayedMessages = listOf(userMessage(id = 42, sessionId = 7, content = "target")),
            onSwitchBranchToMessage = requestedBranchSwitches::add,
        )
        coordinator.onChatContextChanged(
            isUserAuthenticated = true,
            selectedSessionId = 7L,
            activeChatSessionId = 7L,
            isSessionLoaded = true,
            currentLeafMessageId = 6L,
            displayedMessages = previousThread,
            onSwitchBranchToMessage = requestedBranchSwitches::add,
        )

        assertEquals(emptyList(), requestedSessions)
        assertEquals(listOf(42L, 6L), requestedBranchSwitches)
        assertNull(coordinator.uiState.value.rollbackTarget)
        assertNull(coordinator.uiState.value.rollbackContextSessionId)
        assertFalse(coordinator.uiState.value.canReturnToPreviousThread)
    }

    /**
     * Confirms authentication loss cancels pending navigation instead of performing stale follow-up work.
     */
    @Test
    fun onChatContextChanged_clearsPendingNavigationWhenUnauthenticated() {
        val coordinator = ChatSearchCoordinator()
        val requestedBranchSwitches = mutableListOf<Long>()

        coordinator.beginCrossSessionNavigation(
            result = searchResult(sessionId = 21, messageId = 99),
            query = "target",
        )

        coordinator.onChatContextChanged(
            isUserAuthenticated = false,
            selectedSessionId = 21L,
            activeChatSessionId = 21L,
            isSessionLoaded = true,
            currentLeafMessageId = null,
            displayedMessages = emptyList(),
            onSwitchBranchToMessage = requestedBranchSwitches::add,
        )

        coordinator.onChatContextChanged(
            isUserAuthenticated = true,
            selectedSessionId = 21L,
            activeChatSessionId = 21L,
            isSessionLoaded = true,
            currentLeafMessageId = null,
            displayedMessages = emptyList(),
            onSwitchBranchToMessage = requestedBranchSwitches::add,
        )

        assertEquals(emptyList(), requestedBranchSwitches)
    }

    /**
     * Ensures switching sessions still clears session-local search state even when the coordinator is reused.
     */
    @Test
    fun onChatContextChanged_resetsSearchStateWhenSelectedSessionChanges() {
        val coordinator = ChatSearchCoordinator()

        coordinator.onChatContextChanged(
            isUserAuthenticated = true,
            selectedSessionId = 30L,
            activeChatSessionId = 30L,
            isSessionLoaded = true,
            currentLeafMessageId = 1L,
            displayedMessages = listOf(userMessage(id = 1, sessionId = 30, content = "alpha")),
            onSwitchBranchToMessage = {},
        )
        coordinator.showSearch()
        coordinator.updateSearchQuery("alpha")

        coordinator.onChatContextChanged(
            isUserAuthenticated = true,
            selectedSessionId = 31L,
            activeChatSessionId = 31L,
            isSessionLoaded = false,
            currentLeafMessageId = null,
            displayedMessages = emptyList(),
            onSwitchBranchToMessage = {},
        )

        val state = coordinator.uiState.value
        assertFalse(state.isSearchActive)
        assertEquals("", state.searchQuery)
        assertEquals(emptyList(), state.searchResults)
        assertEquals(-1, state.currentSearchIndex)
    }

    /**
     * Builds a representative cross-session search result for coordinator tests.
     *
     * @param sessionId Session containing the matching message.
     * @param messageId Matching message identifier.
     * @return Search result DTO suitable for coordinator navigation tests.
     */
    private fun searchResult(sessionId: Long, messageId: Long): MessageSearchResult {
        return MessageSearchResult(
            sessionId = sessionId,
            sessionName = "Session $sessionId",
            messageId = messageId,
            messageRole = ChatMessage.Role.USER,
            snippet = "target",
            matchStartIndex = 0,
            matchEndExclusive = 6,
            createdAt = instant(messageId),
        )
    }
}
