package eu.torvian.chatbot.app.domain.events

/**
 * Event emitted when a Snackbar interaction (action performed, dismissed, or timed out) occurs.
 * This communicates back from the UI layer (Composables) to ViewModels or other logic.
 *
 * @param originalAppEventId The ID of the AppEvent that originally triggered the Snackbar.
 * @param isActionPerformed True if the Snackbar's action button was clicked; false otherwise (dismissed by timeout or user).
 */
data class SnackbarInteractionEvent(
    val originalAppEventId: String,
    val isActionPerformed: Boolean
) : AppEvent()