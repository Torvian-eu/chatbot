package eu.torvian.chatbot.app.chat.search

import eu.torvian.chatbot.app.testutils.data.instant
import eu.torvian.chatbot.app.testutils.data.userMessage
import eu.torvian.chatbot.common.models.api.core.MessageSearchResult
import eu.torvian.chatbot.common.models.core.ChatMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies staged cross-session search navigation behavior owned by [ChatSearchCoordinator].
 */
class ChatSearchCoordinatorTest {
    /**
     * Ensures each newly staged navigation request emits a distinct reconciliation trigger.
     */
    @Test
    fun beginCrossSessionNavigation_incrementsNavigationRequestVersion() {
        val coordinator = ChatSearchCoordinator()

        assertEquals(0L, coordinator.navigationRequestVersion.value)

        coordinator.beginCrossSessionNavigation(searchResult(sessionId = 42L, messageId = 1L), "needle")
        coordinator.beginCrossSessionNavigation(searchResult(sessionId = 42L, messageId = 2L), "needle")

        assertEquals(2L, coordinator.navigationRequestVersion.value)
    }

    /**
     * Ensures a staged navigation can resolve against the already active session after reconciliation
     * re-runs with otherwise unchanged chat context.
     */
    @Test
    fun onChatContextChanged_resolvesNavigationForAlreadyActiveSession() {
        val coordinator = ChatSearchCoordinator()
        val displayedMessages = listOf(
            userMessage(id = 1L, sessionId = 42L, content = "prefix"),
            userMessage(id = 2L, sessionId = 42L, content = "needle in current session"),
        )
        val branchSwitchRequests = mutableListOf<Long>()

        coordinator.onChatContextChanged(
            isUserAuthenticated = true,
            selectedSessionId = 42L,
            activeChatSessionId = 42L,
            isSessionLoaded = true,
            displayedMessages = displayedMessages,
            onSwitchBranchToMessage = branchSwitchRequests::add,
        )

        coordinator.beginCrossSessionNavigation(
            result = searchResult(sessionId = 42L, messageId = 2L),
            query = "needle",
        )

        coordinator.onChatContextChanged(
            isUserAuthenticated = true,
            selectedSessionId = 42L,
            activeChatSessionId = 42L,
            isSessionLoaded = true,
            displayedMessages = displayedMessages,
            onSwitchBranchToMessage = branchSwitchRequests::add,
        )

        assertTrue(branchSwitchRequests.isEmpty())
        assertTrue(coordinator.uiState.value.isSearchActive)
        assertEquals("needle", coordinator.uiState.value.searchQuery)
        assertEquals(listOf(2L), coordinator.uiState.value.searchResults.map { match -> match.messageId })
        assertEquals(0, coordinator.uiState.value.currentSearchIndex)
    }

    /**
     * Creates a minimal server search-hit DTO suitable for coordinator tests.
     *
     * @param sessionId Session that owns the hit.
     * @param messageId Message identified by the hit.
     * @return Search-hit DTO matching the production navigation contract.
     */
    private fun searchResult(sessionId: Long, messageId: Long): MessageSearchResult = MessageSearchResult(
        sessionId = sessionId,
        sessionName = "Session $sessionId",
        messageId = messageId,
        messageRole = ChatMessage.Role.USER,
        snippet = "needle",
        matchStartIndex = 0,
        matchEndExclusive = 6,
        createdAt = instant(1L),
    )
}