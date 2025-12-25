package eu.torvian.chatbot.app.service.clipboard

/**
 * Service interface for clipboard operations across all platforms.
 *
 * Platform-specific implementations handle the actual clipboard access
 * using the appropriate APIs for each target (Desktop, Android, WasmJs).
 */
interface ClipboardService {
    /**
     * Copies the given text to the system clipboard.
     *
     * @param text The text to copy to the clipboard.
     * @throws Exception if the clipboard operation fails.
     */
    suspend fun copyToClipboard(text: String)
}

