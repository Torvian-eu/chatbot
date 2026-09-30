package eu.torvian.chatbot.app.service.turnnotification

import eu.torvian.chatbot.app.domain.TurnOutcome
import eu.torvian.chatbot.app.domain.events.TurnLifecycleTrigger
import eu.torvian.chatbot.app.generated.resources.Res
import eu.torvian.chatbot.app.generated.resources.turn_notification_body_awaiting_approval
import eu.torvian.chatbot.app.generated.resources.turn_notification_body_turn_completed_failure
import eu.torvian.chatbot.app.generated.resources.turn_notification_body_turn_completed_success
import eu.torvian.chatbot.app.generated.resources.turn_notification_title_unknown_session
import org.jetbrains.compose.resources.getString

/**
 * [TurnNotificationTextSource] backed by the app's bundled strings.
 *
 * Using Compose Resources keeps the alert text localizable through the same mechanism as the rest of
 * the UI, and confines the resource lookups to one place.
 */
class ComposeTurnNotificationTextSource : TurnNotificationTextSource {

    override suspend fun content(
        trigger: TurnLifecycleTrigger,
        sessionName: String?
    ): TurnNotificationContent = TurnNotificationContent(
        title = sessionName ?: getString(Res.string.turn_notification_title_unknown_session),
        body = when (trigger) {
            is TurnLifecycleTrigger.TurnCompleted -> when (trigger.outcome) {
                TurnOutcome.SUCCESS ->
                    getString(Res.string.turn_notification_body_turn_completed_success)

                TurnOutcome.FAILURE ->
                    getString(Res.string.turn_notification_body_turn_completed_failure)
            }

            is TurnLifecycleTrigger.AwaitingApproval ->
                getString(Res.string.turn_notification_body_awaiting_approval)
        }
    )
}
