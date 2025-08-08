package eu.torvian.chatbot.app.compose.preview

import androidx.compose.runtime.Composable
import eu.torvian.chatbot.app.compose.common.LoadingOverlay
import org.jetbrains.compose.ui.tooling.preview.Preview

@Preview
@Composable
fun LoadingOverlayPreview() {
    LoadingOverlay(delayMs = 5000L)
}