package eu.torvian.chatbot.app.service.auth

import kotlinx.io.files.Path

/**
 * Actual Android implementation for setting secure file permissions.
 * On Android, file permissions are handled differently and this is a no-op.
 * Android apps run in their own sandbox with appropriate security restrictions.
 */
internal actual fun setSecureFilePermissions(path: Path) {
    // No-op on Android - the app sandbox provides security isolation
}
