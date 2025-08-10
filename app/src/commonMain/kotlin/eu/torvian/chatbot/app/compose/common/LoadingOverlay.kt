package eu.torvian.chatbot.app.compose.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * The amount of time to delay before showing the loading indicator.
 */
const val LOADING_INDICATOR_DISPLAY_DELAY_MS = 500L

/**
 * The tag to use for testing the loading overlay.
 */
const val LOADING_OVERLAY_TAG = "LoadingOverlay"

/**
 * A loading indicator that is delayed by a certain amount of time before it actually shows.
 * This is useful for cases where we want to avoid showing a loading indicator for very short operations.
 *
 * @param modifier The modifier to apply to the loading indicator.
 * @param delayMs The amount of time to delay before showing the loading indicator.
 */
@Composable
fun LoadingOverlay(
    modifier: Modifier = Modifier,
    delayMs: Long = LOADING_INDICATOR_DISPLAY_DELAY_MS
) {
    var showIndicatorActually by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(delayMs)
        showIndicatorActually = true
    }

    Box(modifier = modifier.testTag(LOADING_OVERLAY_TAG)) {
        if (showIndicatorActually) {
            Box(
                modifier = modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.3f)), // Semi-transparent overlay
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface)
                        .semantics {
                            contentDescription = "Loading indicator"
                        },
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
    }
}