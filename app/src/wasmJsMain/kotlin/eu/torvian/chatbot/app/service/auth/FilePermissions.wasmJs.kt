package eu.torvian.chatbot.app.service.auth

import kotlinx.io.files.Path

/**
 * Actual WASM implementation for setting secure file permissions.
 * On WASM/Web platforms, file permissions are not applicable as files
 * are typically stored in browser storage mechanisms.
 */
internal actual fun setSecureFilePermissions(path: Path) {
    // No-op on WASM - browser security model handles access control
}
