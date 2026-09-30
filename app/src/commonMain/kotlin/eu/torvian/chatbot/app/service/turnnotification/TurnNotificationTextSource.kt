package eu.torvian.chatbot.app.service.turnnotification

import eu.torvian.chatbot.app.domain.events.TurnLifecycleTrigger

/**
 * Turns a [TurnLifecycleTrigger] into the localized text shown for the alert.
 *
 * Implementations carry no message content by design: only the session name and the turn state are
 * expressed, so an alert never leaks conversation text.
 */
interface TurnNotificationTextSource {

    /**
     * Builds the alert text for one trigger.
     *
     * @param trigger Event that caused the alert.
     * @param sessionName Display name of the triggering session, or `null` when it is not in the
     *           loaded session list (freshly spawned or deleted); the implementation falls back to a
     *           generic title in that case.
     * @return Localized title and state description for the alert.
     */
    suspend fun content(trigger: TurnLifecycleTrigger, sessionName: String?): TurnNotificationContent
}
