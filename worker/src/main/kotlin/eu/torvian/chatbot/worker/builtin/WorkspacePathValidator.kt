package eu.torvian.chatbot.worker.builtin

import java.nio.file.Path
import java.nio.file.Paths

/**
 * Thrown when a built-in tool attempts to access a path outside the worker workspace.
 *
 * @property requestedPath The path that was rejected.
 * @property workspace The configured workspace root.
 */
class WorkspaceSecurityViolation(
    val requestedPath: Path,
    val workspace: Path,
    message: String,
) : SecurityException(message)

/**
 * Validates that a path resolved against the workspace stays inside the workspace.
 *
 * The validator resolves `..` segments and normalizes the path before containment check so that
 * requests like `../etc/passwd` are rejected even when the CWD is inside the workspace. Symlinks
 * are resolved via [Path.toRealPath] (best-effort — falls back to the normalized path when the
 * target does not yet exist) to detect symlink escape attempts.
 */
object WorkspacePathValidator {

    /**
     * Resolves [requested] against [workspace] and returns the contained absolute path.
     *
     * @param workspace Absolute workspace root.
     * @param requested Raw user-provided path. May be relative.
     * @return Resolved absolute path that is guaranteed to be inside [workspace].
     * @throws WorkspaceSecurityViolation If the resolved path escapes the workspace.
     */
    fun requireInside(workspace: Path, requested: String): Path {
        val normalizedWorkspace = workspace.toAbsolutePath().normalize()
        val rawCandidate = if (Paths.get(requested).isAbsolute) {
            Paths.get(requested)
        } else {
            normalizedWorkspace.resolve(requested)
        }
        val normalized = rawCandidate.toAbsolutePath().normalize()

        // Attempt to follow symlinks; if the target does not exist yet, fall back to the
        // normalized (lexical) path which still rejects `..` escapes.
        val realPath = try {
            normalized.toRealPath()
        } catch (_: Exception) {
            normalized
        }
        val realWorkspace = try {
            normalizedWorkspace.toRealPath()
        } catch (_: Exception) {
            normalizedWorkspace
        }

        if (!realPath.startsWith(realWorkspace)) {
            throw WorkspaceSecurityViolation(
                requestedPath = realPath,
                workspace = realWorkspace,
                message = "Path '$requested' resolves outside the worker workspace",
            )
        }
        return realPath
    }
}

