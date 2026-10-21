package eu.torvian.chatbot.worker.builtin.impl

import eu.torvian.chatbot.common.models.api.worker.protocol.payload.BuiltInToolExecutionResult
import eu.torvian.chatbot.common.models.tool.BuiltInToolCatalog
import eu.torvian.chatbot.worker.builtin.BuiltInTool
import eu.torvian.chatbot.worker.builtin.BuiltInToolExecutionContext
import eu.torvian.chatbot.worker.builtin.BuiltInToolExecutionError
import eu.torvian.chatbot.worker.builtin.terminal.TerminalSessionError
import eu.torvian.chatbot.worker.builtin.terminal.TerminalSessionManager
import eu.torvian.chatbot.worker.builtin.terminal.TerminalSnapshot
import eu.torvian.chatbot.worker.builtin.terminal.TerminalStatus
import eu.torvian.chatbot.worker.builtin.validation.addUnknownParameterErrors
import eu.torvian.chatbot.worker.builtin.validation.builtInToolErrorResult
import eu.torvian.chatbot.worker.builtin.validation.invalidInputResult
import eu.torvian.chatbot.worker.builtin.validation.parseOptionalInt
import eu.torvian.chatbot.worker.builtin.validation.parseOptionalLong
import eu.torvian.chatbot.worker.builtin.validation.parseRequiredString
import eu.torvian.chatbot.worker.builtin.validation.parseStringArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Built-in tool that owns long-lived PTY sessions.
 *
 * The tool is stateless per call: all state lives in [TerminalSessionManager]. It validates the
 * action-specific arguments, maps typed manager errors to stable tool results, and never throws for
 * a logical failure.
 *
 * @param manager Process-wide session registry and lifecycle owner.
 */
internal class TerminalSessionTool(
    private val manager: TerminalSessionManager,
) : BuiltInTool {
    override val name: String = "terminal_session"
    override val description: String = BuiltInToolCatalog.specFor(name)!!.description
    override val inputSchema: JsonObject = BuiltInToolCatalog.specFor(name)!!.inputSchema

    override suspend fun execute(
        input: JsonObject,
        context: BuiltInToolExecutionContext,
    ): BuiltInToolExecutionResult {
        val validationErrors = mutableListOf<String>()
        val action = parseRequiredString(input, "action", validationErrors)
        val allowedKeys = action?.let { ACTION_KEYS[it] } ?: ALL_KEYS
        addUnknownParameterErrors(input, allowedKeys, validationErrors)

        if (action != null && action !in ACTION_KEYS) {
            validationErrors.add(
                "Argument 'action' must be one of start, send, poll or close; received \"$action\"."
            )
        }

        val waitMillis = parseOptionalLong(
            input,
            "waitMillis",
            manager.config.defaultWaitMillis,
            validationErrors,
        )
        if (waitMillis !in MIN_WAIT_MILLIS..manager.config.maxWaitMillis) {
            validationErrors.add(
                "Argument 'waitMillis' must be between $MIN_WAIT_MILLIS and " +
                    "${manager.config.maxWaitMillis}"
            )
        }
        val maxLines = parseOptionalInt(input, "maxLines", DEFAULT_MAX_LINES, validationErrors)
        if (maxLines <= 0) validationErrors.add("Argument 'maxLines' must be > 0")
        val maxBytes = parseOptionalInt(input, "maxBytes", DEFAULT_MAX_BYTES, validationErrors)
        // The buffer never splits a code point, so a budget below the widest one would return an
        // empty chunk forever and a closed tombstone could never drain.
        if (maxBytes < MIN_MAX_BYTES) {
            validationErrors.add(
                "Argument 'maxBytes' must be >= $MIN_MAX_BYTES so a chunk can hold one " +
                    "maximum-width UTF-8 code point"
            )
        }

        var command: String? = null
        var args: List<String> = emptyList()
        var sessionId: String? = null
        var sessionInput: String? = null
        var idleTimeoutSeconds: Long? = null

        when (action) {
            "start" -> {
                rejectPresent(input, action, validationErrors, "session_id", "input")
                command = parseRequiredString(input, "command", validationErrors)
                command?.let { value ->
                    if (value.any { it.isWhitespace() }) {
                        validationErrors.add(
                            "The 'command' field must be a single executable name without spaces " +
                                "(e.g. \"bash\", \"sh\", \"/usr/bin/ssh\"). Arguments should be provided " +
                                "in the 'args' field. To run a shell command line, invoke the shell " +
                                "explicitly (e.g. command=\"bash\")."
                        )
                    }
                }
                args = parseStringArray(input, "args", validationErrors)
                if (input.containsKey("idleTimeout")) {
                    val requestedIdleTimeout =
                        parseOptionalLong(input, "idleTimeout", 0L, validationErrors)
                    if (requestedIdleTimeout <= 0L) {
                        validationErrors.add("Argument 'idleTimeout' must be > 0")
                    }
                    idleTimeoutSeconds = requestedIdleTimeout
                }
            }

            "send" -> {
                rejectPresent(input, action, validationErrors, "command", "args", "idleTimeout")
                sessionId = parseSessionId(input, validationErrors)
                sessionInput = parseRequiredString(input, "input", validationErrors)
                if (sessionInput != null && sessionInput.isEmpty()) {
                    validationErrors.add("Argument 'input' must not be empty")
                }
            }

            "poll", "close" -> {
                rejectPresent(
                    input,
                    action,
                    validationErrors,
                    "command",
                    "args",
                    "input",
                    "idleTimeout",
                )
                sessionId = parseSessionId(input, validationErrors)
            }
        }

        if (validationErrors.isNotEmpty()) return invalidInputResult(validationErrors)

        val resolvedAction = requireNotNull(action)
        val result = when (resolvedAction) {
            "start" -> manager.start(
                command = requireNotNull(command),
                args = args,
                idleTimeoutSeconds = idleTimeoutSeconds,
                waitMillis = waitMillis,
                maxLines = maxLines,
                maxBytes = maxBytes,
            )

            "send" -> manager.send(
                sessionId = requireNotNull(sessionId),
                input = requireNotNull(sessionInput),
                waitMillis = waitMillis,
                maxLines = maxLines,
                maxBytes = maxBytes,
            )

            "poll" -> manager.poll(
                sessionId = requireNotNull(sessionId),
                waitMillis = waitMillis,
                maxLines = maxLines,
                maxBytes = maxBytes,
            )

            else -> manager.close(
                sessionId = requireNotNull(sessionId),
                maxLines = maxLines,
                maxBytes = maxBytes,
            )
        }

        return result.fold(
            { error -> errorResult(error) },
            { snapshot -> snapshotResult(resolvedAction, snapshot) },
        )
    }

    /**
     * Parses and validates the session handle.
     *
     * @param input Raw tool input.
     * @param validationErrors Accumulated validation errors.
     * @return The valid handle, or null when it is missing or malformed.
     */
    private fun parseSessionId(
        input: JsonObject,
        validationErrors: MutableList<String>,
    ): String? {
        val sessionId = parseRequiredString(input, "session_id", validationErrors)
        if (sessionId != null && !SESSION_ID_PATTERN.matches(sessionId)) {
            validationErrors.add(
                "Argument 'session_id' must be a six-character string over a-z, A-Z and 1-9 " +
                    "(for example \"aB3xY9\"); received \"$sessionId\"."
            )
        }
        return sessionId
    }

    /**
     * Records an error for each field the chosen action must not receive.
     *
     * @param input Raw tool input.
     * @param action Action being validated.
     * @param validationErrors Accumulated validation errors.
     * @param names Forbidden field names.
     */
    private fun rejectPresent(
        input: JsonObject,
        action: String,
        validationErrors: MutableList<String>,
        vararg names: String,
    ) {
        names.forEach { name ->
            if (input.containsKey(name)) {
                validationErrors.add("Argument '$name' is not allowed for action '$action'")
            }
        }
    }

    /**
     * Maps a typed manager failure to a stable tool result.
     *
     * @param error Logical failure.
     * @return Error result, including final output for a closed session.
     */
    private fun errorResult(error: TerminalSessionError): BuiltInToolExecutionResult =
        when (error) {
            is TerminalSessionError.InvalidInput -> invalidInputResult(error.messages)

            is TerminalSessionError.SessionNotFound -> builtInToolErrorResult(
                BuiltInToolExecutionError.NOT_FOUND,
                "No terminal session with id '${error.sessionId}' exists. Start a new session.",
            )

            is TerminalSessionError.SessionNotWritable -> builtInToolErrorResult(
                BuiltInToolExecutionError.EXECUTION_FAILED,
                "Terminal session '${error.sessionId}' is not writable because its process already " +
                    "exited (exitCode=${error.exitCode ?: "unknown"}). Start a new session.",
            )

            is TerminalSessionError.SessionClosed -> {
                val snapshot = error.snapshot
                val output = if (snapshot != null) {
                    outputBody(snapshot)
                } else {
                    "status: ${TerminalStatus.CLOSED}\nsession_id: ${error.sessionId}\n--- output ---\n"
                }
                builtInToolErrorResult(
                    BuiltInToolExecutionError.EXECUTION_FAILED,
                    "Terminal session '${error.sessionId}' is closed (reason=${error.reason.name.lowercase()}); " +
                        "no further output will be produced.",
                ).copy(output = output)
            }

            is TerminalSessionError.SessionLimitReached -> builtInToolErrorResult(
                BuiltInToolExecutionError.EXECUTION_FAILED,
                error.detail,
            )

            is TerminalSessionError.SpawnFailed -> builtInToolErrorResult(
                BuiltInToolExecutionError.EXECUTION_FAILED,
                "Could not start terminal session: ${error.detail}",
            )
        }

    /**
     * Maps a successful snapshot to the documented output and details shape.
     *
     * `pendingBytes`, `droppedBytes` and `bufferBytes` in `details` all count UTF-8 bytes of
     * sanitized output, never characters, so their units match the output budget and the
     * dropped-output notice.
     *
     * @param action Action that produced the snapshot.
     * @param snapshot Session snapshot.
     * @return Successful tool result; a non-zero exit code is not an error.
     */
    private fun snapshotResult(
        action: String,
        snapshot: TerminalSnapshot,
    ): BuiltInToolExecutionResult = BuiltInToolExecutionResult(
        output = outputBody(snapshot),
        isError = false,
        details = buildJsonObject {
            put("action", action)
            put("sessionId", snapshot.sessionId)
            put("status", snapshot.status.name)
            put(
                "exitCode",
                snapshot.exitCode?.let { JsonPrimitive(it) } ?: JsonNull,
            )
            put("hasNewOutput", snapshot.hasNewOutput)
            put("quietForMillis", snapshot.quietForMillis)
            put("truncated", snapshot.truncated)
            put("pendingBytes", snapshot.pendingBytes)
            put("droppedBytes", snapshot.droppedBytes)
            put("bufferBytes", snapshot.bufferBytes)
            put("idleTimeoutSeconds", snapshot.idleTimeoutSeconds)
            put("tuiDetected", snapshot.tuiDetected)
        },
    )

    /**
     * Builds the human-readable header plus the returned chunk.
     *
     * @param snapshot Snapshot to render.
     * @return Header, chunk and the one-time TUI hint when applicable.
     */
    private fun outputBody(snapshot: TerminalSnapshot): String = buildString {
        append("status: ").append(snapshot.status.name).append('\n')
        append("session_id: ").append(snapshot.sessionId).append('\n')
        append("--- output ---\n")
        append(snapshot.output)
        if (snapshot.tuiHint) {
            if (isNotEmpty() && last() != '\n') append('\n')
            append(TUI_HINT)
        }
    }

    private companion object {
        /** Keys accepted by every action plus action-specific keys. */
        val ALL_KEYS = setOf(
            "action",
            "session_id",
            "command",
            "args",
            "input",
            "idleTimeout",
            "waitMillis",
            "maxLines",
            "maxBytes",
        )

        /** Per-action accepted key sets, matching the documented validation table. */
        val ACTION_KEYS: Map<String, Set<String>> = mapOf(
            "start" to setOf(
                "action",
                "command",
                "args",
                "idleTimeout",
                "waitMillis",
                "maxLines",
                "maxBytes",
            ),
            "send" to setOf("action", "session_id", "input", "waitMillis", "maxLines", "maxBytes"),
            "poll" to setOf("action", "session_id", "waitMillis", "maxLines", "maxBytes"),
            "close" to setOf("action", "session_id", "waitMillis", "maxLines", "maxBytes"),
        )

        /** Session handle shape: six characters over `a-z`, `A-Z` and `1-9`. */
        val SESSION_ID_PATTERN = Regex("[a-zA-Z1-9]{6}")

        /** Default lines returned per call. */
        const val DEFAULT_MAX_LINES = 100

        /** Default UTF-8 bytes returned per call. */
        const val DEFAULT_MAX_BYTES = 8000

        /** Smallest accepted byte budget: the UTF-8 width of one maximum-width code point. */
        const val MIN_MAX_BYTES = 4

        /** Minimum accepted per-call wait. */
        const val MIN_WAIT_MILLIS = 100L

        /** One-time warning for programs that use a full-screen terminal UI. */
        const val TUI_HINT =
            "[note: this program appears to use a full-screen terminal UI; terminal_session does " +
                "not reconstruct screens. Use non-interactive flags or redirect output.]\n"
    }
}
