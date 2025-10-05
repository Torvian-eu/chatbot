package eu.torvian.chatbot.app.domain.contracts

import eu.torvian.chatbot.common.models.core.ChatGroup
import eu.torvian.chatbot.common.models.core.ChatSessionSummary

/**
 * Data class to hold the multiple pieces of data needed for the Session List UI when in Success state.
 * It holds the raw lists fetched from the backend and provides the derived grouped structure.
 *
 * @property allSessions The list of all chat sessions fetched from the backend.
 * @property allGroups The list of all chat groups fetched from the backend.
 */
data class SessionListData(
    val allSessions: List<ChatSessionSummary> = emptyList(),
    val allGroups: List<ChatGroup> = emptyList()
) {
    /**
     * Returns the sessions organized by group, ready for display (E6.S2).
     * This is a derived property calculated based on [allSessions] and [allGroups].
     */
    val groupedSessions: Map<ChatGroup?, List<ChatSessionSummary>>
        get() {
            val ungrouped = allSessions.filter { it.groupId == null }.sortedByDescending { it.updatedAt }
            val grouped = allGroups.associateWith { group ->
                allSessions.filter { it.groupId == group.id }.sortedByDescending { it.updatedAt }
            }
            return LinkedHashMap<ChatGroup?, List<ChatSessionSummary>>().apply {
                put(null, ungrouped) // Null key for "Ungrouped" section
                putAll(grouped)
            }
        }
}