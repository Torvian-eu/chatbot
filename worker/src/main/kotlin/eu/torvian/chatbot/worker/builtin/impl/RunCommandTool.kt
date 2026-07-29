package eu.torvian.chatbot.worker.builtin.impl

import eu.torvian.chatbot.common.models.api.worker.protocol.payload.BuiltInToolExecutionResult
import eu.torvian.chatbot.common.models.tool.BuiltInToolCatalog
import eu.torvian.chatbot.worker.builtin.BuiltInTool
import eu.torvian.chatbot.worker.builtin.BuiltInToolExecutionContext
import eu.torvian.chatbot.worker.builtin.BuiltInToolExecutionError
import eu.torvian.chatbot.worker.builtin.validation.*
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.TimeUnit

/**
 * Executes a process inside the worker workspace.
 *
 * The command runs with the workspace as the CWD and is subject to a timeout (defaulting to the
 * worker-configured default). The worker relies on its deployment environment (Docker, VM, etc.)
 * to provide additional isolation.
 */
class RunCommandTool : BuiltInTool {
    override val name: String = "run_command"
    override val description: String = BuiltInToolCatalog.specFor(name)!!.description
    override val inputSchema: JsonObject = BuiltInToolCatalog.specFor(name)!!.inputSchema

    override suspend fun execute(input: JsonObject, context: BuiltInToolExecutionContext): BuiltInToolExecutionResult {
        // Accumulate all INVALID_INPUT validation errors before failing, so the LLM can see
        // every issue at once instead of fixing them one at a time.
        val validationErrors = mutableListOf<String>()

        // Define the set of known/valid parameter names for this tool
        val validKeys = setOf("command", "args", "timeout", "maxLines", "maxBytes")
        addUnknownParameterErrors(input, validKeys, validationErrors)

        val command = parseRequiredString(input, "command", validationErrors)
        if (command != null) {
            // Detect common LLM misuse: the full command line (with arguments) was placed
            // in the `command` field instead of being split into `command` + `args`.
            // Valid executable names passed to ProcessBuilder (which does not invoke a shell)
            // never contain whitespace.
            if (command.any { it.isWhitespace() }) {
                validationErrors.add(
                    "The 'command' field must be a single executable name without spaces " +
                            "(e.g. \"echo\", \"sh\", \"/usr/bin/git\"). Arguments should be provided " +
                            "in the 'args' field. Received: \"$command\". " +
                            "If you need to run a shell command with arguments, invoke the shell " +
                            "explicitly (e.g. command=\"sh\", args=[\"-c\", \"ls -la\"]) or pass " +
                            "arguments individually (e.g. command=\"echo\", args=[\"hello\", \"world\"])."
                )
            }
        }

        val timeoutSeconds = parseOptionalLong(
            input,
            "timeout",
            defaultValue = context.defaultCommandTimeoutSeconds,
            validationErrors = validationErrors,
        )
        val args = parseStringOrStringArray(input, "args", validationErrors)
        val maxLines = parseOptionalInt(input, "maxLines", defaultValue = 50, validationErrors)
        if (maxLines <= 0) {
            validationErrors.add("Argument 'maxLines' must be > 0")
        }
        val maxBytes = parseOptionalInt(input, "maxBytes", defaultValue = 2000, validationErrors)
        if (maxBytes <= 0) {
            validationErrors.add("Argument 'maxBytes' must be > 0")
        }

        if (timeoutSeconds <= 0) {
            validationErrors.add("timeout must be > 0 seconds")
        }

        if (validationErrors.isNotEmpty()) {
            return invalidInputResult(validationErrors)
        }

        return withContext(context.ioDispatcher) {
            try {
                val processBuilder = ProcessBuilder(listOf(command) + args)
                    .directory(context.workspace.toFile())
                    .redirectErrorStream(false)
                val process = processBuilder.start()
                val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
                if (!finished) {
                    process.destroyForcibly()
                    return@withContext builtInToolErrorResult(
                        BuiltInToolExecutionError.TIMEOUT,
                        "Command exceeded timeout of $timeoutSeconds seconds"
                    )
                }
                val stdout = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val stderr = process.errorStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val exitCode = process.exitValue()

                val stdoutResult = truncateLinesAndBytes(stdout, maxLines, maxBytes)
                val stderrResult = truncateLinesAndBytes(stderr, maxLines, maxBytes)
                val truncatedStdout = stdoutResult.text
                val truncatedStderr = stderrResult.text

                val outputBodyBeforeTruncation = buildString {
                    append("exitCode: ").append(exitCode).append('\n')
                    append("--- stdout ---\n").append(truncatedStdout)
                    if (truncatedStderr.isNotEmpty()) {
                        append("\n--- stderr ---\n").append(truncatedStderr)
                    }
                }

                val truncationResult = truncateLinesAndBytes(outputBodyBeforeTruncation, maxLines, maxBytes)
                val outputBody = truncationResult.text
                val linesShown = truncationResult.linesShown
                val bytesShown = truncationResult.bytesShown
                val truncated = truncationResult.isTruncated || stdoutResult.isTruncated || stderrResult.isTruncated

                val notice = if (truncated) {
                    formatTruncationNotice(linesShown, bytesShown)
                } else {
                    ""
                }
                val output = outputBody + notice

                val details = buildJsonObject {
                    put("stdout", truncatedStdout)
                    put("stderr", truncatedStderr)
                    put("exitCode", exitCode)
                    put("timeoutSeconds", timeoutSeconds)
                    put("truncated", truncated)
                }
                BuiltInToolExecutionResult(
                    output = output,
                    isError = exitCode != 0,
                    errorMessage = if (exitCode != 0) "Command exited with code $exitCode" else null,
                    errorCode = if (exitCode != 0) BuiltInToolExecutionError.EXECUTION_FAILED else null,
                    details = details,
                )
            } catch (e: Exception) {
                builtInToolErrorResult(
                    BuiltInToolExecutionError.EXECUTION_FAILED,
                    "Failed to run command: ${e.message}"
                )
            }
        }
    }
}