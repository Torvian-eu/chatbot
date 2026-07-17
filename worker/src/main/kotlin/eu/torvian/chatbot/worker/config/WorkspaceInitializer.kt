package eu.torvian.chatbot.worker.config

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import java.nio.file.Files
import java.nio.file.Path

/**
 * Ensures the worker workspace directory exists, creating it (and any missing parents) if needed.
 *
 * The workspace is the security boundary for built-in file tools and the working directory for
 * `run_command`. It must exist before any built-in tool executes, so this initializer is invoked
 * both during setup and at runtime startup.
 *
 * The operation is idempotent: it succeeds silently when the directory already exists. It fails
 * (rather than silently overwriting) if the path exists but is a regular file, because such a path
 * cannot serve as the workspace root or command CWD.
 *
 * @param workspacePath Absolute, normalized workspace path to initialize.
 * @return Either a logical configuration error or `Unit` when the workspace is ready for use.
 */
fun ensureWorkspaceDirectory(workspacePath: Path): Either<WorkerConfigError, Unit> = either {
    when {
        Files.exists(workspacePath) -> {
            // A pre-existing regular file would block tool execution and command CWD; reject it.
            ensure(Files.isDirectory(workspacePath)) {
                WorkerConfigError.ConfigInvalid(
                    "Workspace path exists but is not a directory: $workspacePath"
                )
            }
        }
        else -> {
            runCatching { Files.createDirectories(workspacePath) }
                .onFailure { cause ->
                    raise(
                        WorkerConfigError.ConfigReadFailed(
                            workspacePath.toString(),
                            "Failed to create workspace directory: ${cause.message}"
                        )
                    )
                }
        }
    }
}

