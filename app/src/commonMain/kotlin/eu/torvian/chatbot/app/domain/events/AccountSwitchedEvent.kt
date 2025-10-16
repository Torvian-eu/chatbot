package eu.torvian.chatbot.app.domain.events

/**
 * Event emitted when the user switches from one account to another.
 *
 * ViewModels and other components can listen to this event to clear cached data
 * and reload data for the new account. This ensures that UI state is properly
 * refreshed when switching between accounts.
 *
 * @property previousUserId The ID of the previously active user (null if none was active)
 * @property newUserId The ID of the newly active user
 */
class AccountSwitchedEvent(
    val previousUserId: Long?,
    val newUserId: Long
) : InternalEvent()
