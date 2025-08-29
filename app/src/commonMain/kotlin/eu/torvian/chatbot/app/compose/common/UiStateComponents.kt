package eu.torvian.chatbot.app.compose.common

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.domain.contracts.UiState
import eu.torvian.chatbot.common.api.ApiError

/**
 * Composable that displays content based on UiState with proper loading and error handling.
 *
 * @param T The type of data in the UiState
 * @param uiState The current UI state
 * @param onRetry Callback for retry action when in error state
 * @param loadingMessage Optional custom loading message
 * @param errorTitle Optional custom error title
 * @param modifier Modifier for styling
 * @param content Composable to display when state is Success
 */
@Composable
fun <T> UiStateContent(
    uiState: UiState<ApiError, T>,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    loadingMessage: String = "Loading...",
    errorTitle: String = "Error",
    content: @Composable (T) -> Unit
) {
    Box(modifier = modifier.fillMaxSize()) {
        when (uiState) {
            is UiState.Idle -> {
                // Show nothing or a placeholder
            }

            is UiState.Loading -> {
                LoadingStateDisplay(
                    message = loadingMessage,
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            is UiState.Error -> {
                ErrorStateDisplay(
                    error = uiState.error,
                    onRetry = onRetry,
                    title = errorTitle,
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            is UiState.Success -> {
                content(uiState.data)
            }
        }
    }
}

/**
 * Composable that displays a loading state with spinner and message.
 *
 * @param message Loading message to display
 * @param modifier Modifier for styling
 */
@Composable
fun LoadingStateDisplay(
    message: String = "Loading...",
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(48.dp),
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
    }
}


/**
 * Composable for displaying inline error messages in forms.
 *
 * @param errorMessage The error message to display
 * @param modifier Modifier for styling
 */
@Composable
fun InlineErrorMessage(
    errorMessage: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = errorMessage,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
        modifier = modifier.padding(start = 16.dp, top = 4.dp)
    )
}

/**
 * Composable for displaying success messages.
 *
 * @param message The success message to display
 * @param modifier Modifier for styling
 */
@Composable
fun SuccessMessage(
    message: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = message,
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.bodyMedium,
        modifier = modifier.padding(16.dp),
        textAlign = TextAlign.Center
    )
}

/**
 * Composable for displaying empty state when lists are empty.
 *
 * @param message The message to display
 * @param actionText Optional action button text
 * @param onAction Optional action button callback
 * @param modifier Modifier for styling
 */
@Composable
fun EmptyStateDisplay(
    message: String,
    modifier: Modifier = Modifier,
    actionText: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier = modifier.padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        if (actionText != null && onAction != null) {
            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onAction,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text(actionText)
            }
        }
    }
}
