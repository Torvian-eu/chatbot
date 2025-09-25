package eu.torvian.chatbot.app.service.auth

import kotlinx.io.files.Path
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/**
 * Actual JVM implementation for setting secure file permissions.
 * It attempts to set POSIX permissions to "read/write for owner only".
 * It fails silently on non-POSIX systems or if an error occurs.
 */
internal actual fun setSecureFilePermissions(path: Path) {
    try {
        val nioPath = java.nio.file.Paths.get(path.toString())
        if (nioPath.fileSystem.supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(
                nioPath,
                PosixFilePermissions.fromString("rw-------")
            )
        }
    } catch (_: Exception) {
        // Ignore... operation is best-effort on desktop.
    }
}
