package eu.torvian.chatbot.app.service.clipboard

import kotlinx.browser.window

/**
 * WasmJs implementation of [ClipboardService] using the Web Clipboard API.
 *
 * Uses the browser's navigator.clipboard.writeText API to copy text to the clipboard.
 * Note: This requires a secure context (HTTPS) and may prompt user permission on some browsers.
 */
class ClipboardServiceWasmJs : ClipboardService {

    @OptIn(ExperimentalWasmJsInterop::class)
    override suspend fun copyToClipboard(text: String) {
        window.navigator.clipboard.writeText(text)
    }
}

