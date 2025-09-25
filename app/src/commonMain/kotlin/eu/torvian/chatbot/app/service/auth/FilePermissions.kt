package eu.torvian.chatbot.app.service.auth

import kotlinx.io.files.Path

/**
 * Sets secure file permissions on the given path, if the platform supports it.
 * This is an `expect` function, requiring platform-specific implementations.
 * On non-POSIX systems, this may be a no-op.
 */
internal expect fun setSecureFilePermissions(path: Path)
