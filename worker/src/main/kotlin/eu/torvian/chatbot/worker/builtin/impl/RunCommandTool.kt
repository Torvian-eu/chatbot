package eu.torvian.chatbot.worker.builtin.impl

import eu.torvian.chatbot.common.models.api.worker.protocol.payload.BuiltInToolExecutionResult
import eu.torvian.chatbot.common.models.tool.BuiltInToolCatalog
import eu.torvian.chatbot.worker.builtin.BuiltInTool
import eu.torvian.chatbot.worker.builtin.BuiltInToolExecutionContext
import eu.torvian.chatbot.worker.builtin.BuiltInToolExecutionError
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
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
        val command = input["command"]?.jsonPrimitive?.content
            ?: return errorResult(BuiltInToolExecutionError.INVALID_INPUT, "Missing required argument: command")
        val args = (input["args"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.content } ?: emptyList()
        val timeoutSeconds = input["timeout"]?.jsonPrimitive?.content?.toLongOrNull()
            ?: context.defaultCommandTimeoutSeconds

        if (timeoutSeconds <= 0) {
            return errorResult(BuiltInToolExecutionError.INVALID_INPUT, "timeout must be > 0 seconds")
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
                    return@withContext errorResult(
                        BuiltInToolExecutionError.TIMEOUT,
                        "Command exceeded timeout of $timeoutSeconds seconds"
                    )
                }
                val stdout = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val stderr = process.errorStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val exitCode = process.exitValue()
                val details = buildJsonObject {
                    put("stdout", stdout)
                    put("stderr", stderr)
                    put("exitCode", exitCode)
                    put("timeoutSeconds", timeoutSeconds)
                }
                BuiltInToolExecutionResult(
                    output = buildString {
                        append("exitCode: ").append(exitCode).append('\n')
                        append("--- stdout ---\n").append(stdout)
                        if (stderr.isNotEmpty()) {
                            append("\n--- stderr ---\n").append(stderr)
                        }
                    },
                    isError = exitCode != 0,
                    errorMessage = if (exitCode != 0) "Command exited with code $exitCode" else null,
                    errorCode = if (exitCode != 0) BuiltInToolExecutionError.EXECUTION_FAILED else null,
                    details = details,
                )
            } catch (e: Exception) {
                errorResult(BuiltInToolExecutionError.EXECUTION_FAILED, "Failed to run command: ${e.message}")
            }
        }
    }

    private fun errorResult(code: String, message: String): BuiltInToolExecutionResult =
        BuiltInToolExecutionResult(isError = true, errorMessage = message, errorCode = code)
}
