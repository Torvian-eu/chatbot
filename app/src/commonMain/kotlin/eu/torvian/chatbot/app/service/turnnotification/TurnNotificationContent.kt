package eu.torvian.chatbot.app.service.turnnotification

/**
 * Localized, privacy-safe text shown for one turn alert.
 *
 * @property title Short headline naming the session the alert belongs to.
 * @property body State description of the alert (completed successfully, failed, awaiting approval).
 */
data class TurnNotificationContent(
    val title: String,
    val body: String
)
