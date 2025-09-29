package eu.torvian.chatbot.app.compose.snackbar

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Shared snackbar implementation for reuse in Auth and Main screens.
 */
@Composable
fun SharedSnackbar(
    data: SnackbarData,
    visualsWithError: SnackbarVisualsWithError?
) {
    val containerColor: Color
    val contentColor: Color
    val actionColor: Color
    val actionContentColor: Color
    val dismissActionContentColor: Color

    if (visualsWithError?.isError == true) {
        containerColor = androidx.compose.material3.MaterialTheme.colorScheme.errorContainer
        contentColor = androidx.compose.material3.MaterialTheme.colorScheme.onErrorContainer
        actionColor = androidx.compose.material3.MaterialTheme.colorScheme.error
        actionContentColor = androidx.compose.material3.MaterialTheme.colorScheme.error
        dismissActionContentColor = androidx.compose.material3.MaterialTheme.colorScheme.onErrorContainer
    } else {
        containerColor = androidx.compose.material3.MaterialTheme.colorScheme.inverseSurface
        contentColor = androidx.compose.material3.MaterialTheme.colorScheme.inverseOnSurface
        actionColor = androidx.compose.material3.MaterialTheme.colorScheme.inversePrimary
        actionContentColor = androidx.compose.material3.MaterialTheme.colorScheme.inversePrimary
        dismissActionContentColor = androidx.compose.material3.MaterialTheme.colorScheme.inverseOnSurface
    }

    Snackbar(
        snackbarData = data,
        modifier = Modifier.padding(12.dp),
        containerColor = containerColor,
        contentColor = contentColor,
        actionColor = actionColor,
        actionContentColor = actionContentColor,
        dismissActionContentColor = dismissActionContentColor
    )
}
