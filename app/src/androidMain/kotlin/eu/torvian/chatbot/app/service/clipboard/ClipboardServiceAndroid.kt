package eu.torvian.chatbot.app.service.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/**
 * Android implementation of [ClipboardService] using Android's ClipboardManager.
 *
 * Uses the Android [ClipboardManager] system service to copy text to the clipboard.
 *
 * @param context The Android application context for accessing system services.
 */
class ClipboardServiceAndroid(
    private val context: Context
) : ClipboardService {

    override suspend fun copyToClipboard(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("message", text)
        clipboard.setPrimaryClip(clip)
    }
}

