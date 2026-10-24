package eu.torvian.chatbot.worker.builtin.impl

import arrow.core.Either
import eu.torvian.chatbot.worker.builtin.BuiltInToolExecutionError
import eu.torvian.chatbot.worker.builtin.terminal.TerminalCloseReason
import eu.torvian.chatbot.worker.builtin.terminal.TerminalSessionError
import eu.torvian.chatbot.worker.builtin.terminal.TerminalSessionManager
import eu.torvian.chatbot.worker.builtin.terminal.TerminalSnapshot
import eu.torvian.chatbot.worker.builtin.terminal.TerminalStatus
import eu.torvian.chatbot.worker.builtin.terminal.testTerminalConfig
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Validation, dispatch and result-mapping tests for [TerminalSessionTool], using a mocked manager so
 * no process is started.
 */
class TerminalSessionToolTest {

    private val config = testTerminalConfig()
    private lateinit var manager: TerminalSessionManager
    private lateinit var tool: TerminalSessionTool

    /** Creates the mocked manager and tool for each test. */
    @BeforeTest
    fun setUp() {
        manager = mockk()
        every { manager.config } returns config
        tool = TerminalSessionTool(manager)
    }

    /** Releases MockK state after each test. */
    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    /**
     * @param action Action value to place in the input.
     * @param extra Additional fields.
     * @return Tool input object.
     */
    private fun input(action: String, extra: JsonObjectBuilder.() -> Unit = {}): JsonObject = buildJsonObject {
        put("action", action)
        extra()
    }

    /**
     * @param result Tool result to inspect.
     * @return Validation messages collected from `errorDetails`.
     */
    private fun validationErrors(result: eu.torvian.chatbot.common.models.api.worker.protocol.payload.BuiltInToolExecutionResult): String {
        assertTrue(result.isError)
        assertEquals(BuiltInToolExecutionError.INVALID_INPUT, result.errorCode)
        return assertNotNull(result.errorDetails)
    }

    /**
     * @param result Tool result to inspect.
     * @return The individual validation messages carried by `errorDetails`.
     */
    private fun parseValidationErrors(
        result: eu.torvian.chatbot.common.models.api.worker.protocol.payload.BuiltInToolExecutionResult,
    ): List<String> =
        assertNotNull(result.errorDetails)
            .let { Json.parseToJsonElement(it).jsonObject.getValue("validationErrors").jsonArray }
            .map { it.jsonPrimitive.content }

    @Test
    fun `unknown action is rejected`(): Unit = runBlocking {
        val result = tool.execute(input("resume"), context())

        assertTrue(validationErrors(result).contains("action"))
    }

    @Test
    fun `start requires a single executable command`(): Unit = runBlocking {
        val missing = tool.execute(input("start"), context())
        assertTrue(validationErrors(missing).contains("Missing required argument: command"))

        val withSpaces = tool.execute(input("start") { put("command", "bash -lc echo") }, context())
        assertTrue(validationErrors(withSpaces).contains("single executable"))
    }

    @Test
    fun `start forbids session id and input`(): Unit = runBlocking {
        val result = tool.execute(
            input("start") {
                put("command", "bash")
                put("session_id", "aB3xY9")
                put("input", "x")
            },
            context(),
        )

        val errors = validationErrors(result)
        assertTrue(errors.contains("session_id"))
        assertTrue(errors.contains("input"))
    }

    @Test
    fun `send requires a valid session id and non-empty input`(): Unit = runBlocking {
        val result = tool.execute(
            input("send") {
                put("session_id", "1")
                put("input", "")
                put("command", "bash")
            },
            context(),
        )

        val errors = validationErrors(result)
        assertTrue(errors.contains("six-character string"))
        assertTrue(errors.contains("must not be empty"))
        assertTrue(errors.contains("command"))
    }

    @Test
    fun `poll and close forbid input and command`(): Unit = runBlocking {
        val poll = tool.execute(
            input("poll") {
                put("session_id", "aB3xY9")
                put("input", "x")
                put("command", "bash")
            },
            context(),
        )
        val close = tool.execute(
            input("close") {
                put("session_id", "aB3xY9")
                put("args", jsonArrayOf("x"))
            },
            context(),
        )

        assertTrue(validationErrors(poll).contains("input"))
        assertTrue(validationErrors(close).contains("args"))
    }

    @Test
    fun `unknown parameters are reported for the action`(): Unit = runBlocking {
        val result = tool.execute(
            input("poll") {
                put("session_id", "aB3xY9")
                put("bogus", true)
            },
            context(),
        )

        assertTrue(validationErrors(result).contains("Unknown parameter: 'bogus'"))
    }

    /**
     * A key the tool advertises but this action does not accept must be reported once, as forbidden for
     * the action, and never also as an unknown parameter.
     */
    @Test
    fun `credential sent with a non-attach action yields one message`(): Unit = runBlocking {
        listOf("start", "send", "poll", "detach", "close").forEach { action ->
            val result = tool.execute(
                input(action) {
                    put("session_id", "aB3xY9")
                    put("input", "x")
                    put("command", "bash")
                    put("credential", "vps")
                },
                context(),
            )

            val errors = parseValidationErrors(result)
            assertEquals(
                listOf("Argument 'credential' is not allowed for action '$action'"),
                errors.filter { it.contains("credential") },
                "Unexpected credential messages for action '$action': $errors",
            )
        }
    }

    @Test
    fun `wait and size limits are validated`(): Unit = runBlocking {
        val result = tool.execute(
            input("poll") {
                put("session_id", "aB3xY9")
                put("waitMillis", 1)
                put("maxLines", 0)
                put("maxBytes", 0)
            },
            context(),
        )

        val errors = validationErrors(result)
        assertTrue(errors.contains("waitMillis"))
        assertTrue(errors.contains("maxLines"))
        assertTrue(errors.contains("maxBytes"))
    }

    @Test
    fun `start dispatches the exact command and args to the manager`(): Unit = runBlocking {
        val snapshot = snapshot(status = TerminalStatus.QUIET)
        coEvery {
            manager.start("bash", listOf("-l"), null, 150L, 100, 8000)
        } returns Either.Right(snapshot)

        val result = tool.execute(
            input("start") {
                put("command", "bash")
                putJsonArray("args") { add("-l") }
            },
            context(),
        )

        assertFalse(result.isError)
        coVerify {
            manager.start("bash", listOf("-l"), null, 150L, 100, 8000)
        }
    }

    @Test
    fun `send and poll forward the validated session id`(): Unit = runBlocking {
        coEvery { manager.send("aB3xY9", "hi\n", 150L, 100, 8000) } returns Either.Right(snapshot())
        coEvery { manager.poll("aB3xY9", 150L, 100, 8000) } returns Either.Right(snapshot())

        val send = tool.execute(
            input("send") {
                put("session_id", "aB3xY9")
                put("input", "hi\n")
            },
            context(),
        )
        val poll = tool.execute(
            input("poll") { put("session_id", "aB3xY9") },
            context(),
        )

        assertFalse(send.isError)
        assertFalse(poll.isError)
        coVerify { manager.send("aB3xY9", "hi\n", 150L, 100, 8000) }
        coVerify { manager.poll("aB3xY9", 150L, 100, 8000) }
    }

    @Test
    fun `snapshot maps to the documented output and details shape`(): Unit = runBlocking {
        val snapshot = snapshot(
            status = TerminalStatus.EXITED,
            output = "done\n",
            exitCode = 3,
            pendingBytes = 12,
            hasNewOutput = true,
            tuiDetected = true,
            tuiHint = true,
        )
        coEvery { manager.poll("aB3xY9", 150L, 100, 8000) } returns Either.Right(snapshot)

        val result = tool.execute(input("poll") { put("session_id", "aB3xY9") }, context())

        assertFalse(result.isError, "a non-zero exit code is not a tool error")
        val output = assertNotNull(result.output)
        assertTrue(output.startsWith("status: EXITED\nsession_id: aB3xY9\n--- output ---\n"))
        assertTrue(output.contains("done"))
        assertTrue(output.contains("full-screen terminal UI"))
        val details = assertNotNull(result.details)
        assertTrue(details.toString().contains("\"sessionId\":\"aB3xY9\""))
        assertTrue(details.toString().contains("\"quietForMillis\""))
        assertTrue(details.toString().contains("\"truncated\":true"))
        assertTrue(details.toString().contains("\"exitCode\":3"))
    }

    @Test
    fun `unknown session maps to NOT_FOUND`(): Unit = runBlocking {
        coEvery { manager.poll("aB3xY9", 150L, 100, 8000) } returns
            Either.Left(TerminalSessionError.SessionNotFound("aB3xY9"))

        val result = tool.execute(input("poll") { put("session_id", "aB3xY9") }, context())

        assertTrue(result.isError)
        assertEquals(BuiltInToolExecutionError.NOT_FOUND, result.errorCode)
    }

    @Test
    fun `send to an exited process maps to EXECUTION_FAILED`(): Unit = runBlocking {
        coEvery { manager.send("aB3xY9", "x\n", 150L, 100, 8000) } returns
            Either.Left(TerminalSessionError.SessionNotWritable("aB3xY9", 7))

        val result = tool.execute(
            input("send") {
                put("session_id", "aB3xY9")
                put("input", "x\n")
            },
            context(),
        )

        assertTrue(result.isError)
        assertEquals(BuiltInToolExecutionError.EXECUTION_FAILED, result.errorCode)
        assertTrue(assertNotNull(result.errorMessage).contains("exitCode=7"))
    }

    @Test
    fun `poll on a closed tombstone maps to a non-error result with the final chunk`(): Unit = runBlocking {
        val closedSnapshot = snapshot(
            status = TerminalStatus.CLOSED,
            output = "last\n",
        )
        coEvery { manager.poll("aB3xY9", 150L, 100, 8000) } returns Either.Right(closedSnapshot)

        val result = tool.execute(input("poll") { put("session_id", "aB3xY9") }, context())

        assertFalse(result.isError, "reading a closed session is not a tool error")
        assertEquals(null, result.errorCode)
        val output = assertNotNull(result.output)
        assertTrue(output.startsWith("status: CLOSED\nsession_id: aB3xY9\n--- output ---\n"), output)
        assertTrue(output.contains("last"))
        val details = assertNotNull(result.details)
        assertTrue(details.toString().contains("\"status\":\"CLOSED\""), details.toString())
    }

    @Test
    fun `send to a closed tombstone keeps the final output and maps to EXECUTION_FAILED`(): Unit = runBlocking {
        val finalSnapshot = snapshot(
            status = TerminalStatus.CLOSED,
            output = "last\n",
        )
        coEvery { manager.send("aB3xY9", "x\n", 150L, 100, 8000) } returns
            Either.Left(
                TerminalSessionError.SessionClosed(
                    "aB3xY9",
                    TerminalCloseReason.IDLE_TIMEOUT,
                    finalSnapshot,
                )
            )

        val result = tool.execute(
            input("send") {
                put("session_id", "aB3xY9")
                put("input", "x\n")
            },
            context(),
        )

        assertTrue(result.isError)
        assertEquals(BuiltInToolExecutionError.EXECUTION_FAILED, result.errorCode)
        val output = assertNotNull(result.output)
        assertTrue(output.contains("status: CLOSED"))
        assertTrue(output.contains("last"))
        assertTrue(assertNotNull(result.errorMessage).contains("idle_timeout"))
    }

    @Test
    fun `maxBytes below one maximum-width code point is rejected`(): Unit = runBlocking {
        coEvery { manager.poll("aB3xY9", 150L, 100, 4) } returns Either.Right(snapshot())

        // 1-3 bytes cannot hold a 4-byte code point, so a chunk could never advance the cursor.
        listOf(1, 2, 3).forEach { bytes ->
            val result = tool.execute(
                input("poll") {
                    put("session_id", "aB3xY9")
                    put("maxBytes", bytes)
                },
                context(),
            )

            val errors = validationErrors(result)
            assertTrue(errors.contains("maxBytes"), "maxBytes=$bytes was not rejected: $errors")
        }

        val accepted = tool.execute(
            input("poll") {
                put("session_id", "aB3xY9")
                put("maxBytes", 4)
            },
            context(),
        )

        assertFalse(accepted.isError, "maxBytes=4 was rejected: ${accepted.errorDetails}")
    }

    @Test
    fun `catalog schema requires maxBytes to fit one maximum-width code point`() {
        val maxBytes = assertNotNull(
            tool.inputSchema["properties"]?.jsonObject?.get("maxBytes")
        ).jsonObject

        assertEquals(4, assertNotNull(maxBytes["minimum"]).jsonPrimitive.int)
    }

    @Test
    fun `session limit and spawn failures map to EXECUTION_FAILED`(): Unit = runBlocking {
        coEvery {
            manager.start("bash", emptyList(), null, 150L, 100, 8000)
        } answers {
            Either.Left(TerminalSessionError.SessionLimitReached("limit"))
        }

        val result = tool.execute(input("start") { put("command", "bash") }, context())

        assertTrue(result.isError)
        assertEquals(BuiltInToolExecutionError.EXECUTION_FAILED, result.errorCode)
    }

    /**
     * @return Execution context using a temporary workspace.
     */
    private fun context() = eu.torvian.chatbot.worker.builtin.BuiltInToolExecutionContext(
        workspace = Path.of("/tmp"),
        defaultCommandTimeoutSeconds = 10,
        defaultSearchTimeoutSeconds = 5,
    )

    /**
     * @param status Status to report.
     * @param output Chunk to return.
     * @param exitCode Optional exit code.
     * @param pendingBytes Pending bytes that drive `truncated`.
     * @param hasNewOutput Whether output was returned.
     * @param tuiDetected Whether TUI output was seen.
     * @param tuiHint Whether this read should show the hint.
     * @return Snapshot for the mocked manager.
     */
    private fun snapshot(
        status: TerminalStatus = TerminalStatus.QUIET,
        output: String = "hi\n",
        exitCode: Int? = null,
        pendingBytes: Int = 0,
        hasNewOutput: Boolean = true,
        tuiDetected: Boolean = false,
        tuiHint: Boolean = false,
    ) = TerminalSnapshot(
        sessionId = "aB3xY9",
        status = status,
        output = output,
        exitCode = exitCode,
        hasNewOutput = hasNewOutput,
        quietForMillis = 42,
        truncated = pendingBytes > 0,
        pendingBytes = pendingBytes,
        droppedBytes = 0,
        bufferBytes = output.length,
        idleTimeoutSeconds = 60,
        tuiDetected = tuiDetected,
        tuiHint = tuiHint,
    )

    /**
     * @param values Strings to encode as a JSON array.
     * @return JSON array containing [values].
     */
    private fun jsonArrayOf(vararg values: String) = buildJsonArray {
        values.forEach { add(it) }
    }
}
