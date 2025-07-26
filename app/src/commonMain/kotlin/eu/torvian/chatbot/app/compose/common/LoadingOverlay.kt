package eu.torvian.chatbot.app.compose.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator // Changed from material to material3
import androidx.compose.material3.MaterialTheme // Changed from material to material3
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * A simple loading overlay component.
 */
@Composable
fun LoadingOverlay(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.3f)), // Semi-transparent overlay
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape) // Optional: make it circular
                .background(MaterialTheme.colorScheme.surface), // Use M3 color scheme
            color = MaterialTheme.colorScheme.secondary // Use M3 color scheme
        )
    }
}