package eu.torvian.chatbot.app.compose.snackbar

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarVisuals

/**
 * A custom SnackbarVisuals implementation with an additional `isError` flag.
 *
 * @param isError Whether the original event was an error (determines colors)
 * @param message The message to display in the Snackbar
 * @param actionLabel The label for the action button, if any
 * @param withDismissAction Whether to show a dismiss action (defaults to true)
 * @param duration The duration for which the Snackbar should be shown (defaults to SnackbarDuration.Short if no actionLabel, SnackbarDuration.Indefinite otherwise)
 */
data class SnackbarVisualsWithError(
    val isError: Boolean,
    override val message: String,
    override val actionLabel: String?,
    override val withDismissAction: Boolean = true,
    override val duration: SnackbarDuration =
        if (actionLabel != null) SnackbarDuration.Indefinite else SnackbarDuration.Short
) : SnackbarVisuals