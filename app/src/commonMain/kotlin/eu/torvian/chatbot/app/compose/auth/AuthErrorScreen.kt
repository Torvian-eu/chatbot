package eu.torvian.chatbot.app.compose.auth

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Error screen shown when authentication initialization fails.
 *
 * @param errorMessage The error message to display
 * @param onRetry Callback to retry the operation
 * @param onGoToLogin Callback to navigate to login screen
 */
@Composable
fun AuthErrorScreen(
    errorMessage: String,
    onRetry: () -> Unit,
    onGoToLogin: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Error Icon
        Icon(
            imageVector = Icons.Default.Error,
            contentDescription = "Error",
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier
                .size(64.dp)
                .padding(bottom = 16.dp)
        )

        // Error Title
        Text(
            text = "Authentication Error",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        // Error Message
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(16.dp)
            )
        }

        // Action Buttons
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Retry Button
            Button(
                onClick = onRetry,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Retry")
            }

            // Go to Login Button
            OutlinedButton(
                onClick = onGoToLogin,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Go to Login")
            }
        }
    }
}
