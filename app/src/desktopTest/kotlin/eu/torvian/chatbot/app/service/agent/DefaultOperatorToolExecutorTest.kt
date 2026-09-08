package eu.torvian.chatbot.app.service.agent

import eu.torvian.chatbot.common.models.api.core.ChatClientEvent
import eu.torvian.chatbot.common.models.tool.OperatorToolCatalog
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [DefaultOperatorToolExecutor], the central operator-tool executor that dispatches each
 * request to the [OperatorTool] registered for the tool's catalog name.
 *
 * Verifies that `spawn_agent` and `send_message` calls are routed to their respective tools (with
 * the emitted result relayed back through the sink), and that an unknown tool name fails fast with
 * a readable tool error without delegating anywhere. Routing/relay is verified with a recordable
 * fake [OperatorTool] so no MockK suspend-lambda stubbing is involved.
 */
class DefaultOperatorToolExecutorTest {

    /**
     * Recordable fake [OperatorTool] that records the call arguments and immediately emits the
     * configured result through the client-event sink, mirroring what a real tool does when it
     * finishes its turn.
     *
     * @property result The [ChatClientEvent.ToolExecutionResult] reported to the sink on execute.
     */
    private class RecordingTool(
        private val result: ChatClientEvent.ToolExecutionResult
    ) : OperatorTool {
        /** The tool-call id recorded from the last routed call, or `null` if never called. */
        var toolCallId: Long? = null

        /** The payload recorded from the last routed call, or `null` if never called. */
        var payload: String? = null

        override suspend fun execute(
            toolCallId: Long,
            payload: String,
            clientEvents: suspend (ChatClientEvent.ToolExecutionResult) -> Unit
        ) {
            this.toolCallId = toolCallId
            this.payload = payload
            clientEvents(result)
        }
    }

    @Test
    fun `routes spawn_agent calls to the spawn tool and relays its result`() = runTest {
        val spawnTool = RecordingTool(
            ChatClientEvent.ToolExecutionResult(toolCallId = 1L, output = "spawn ok")
        )
        val sendTool = RecordingTool(
            ChatClientEvent.ToolExecutionResult(toolCallId = 1L, output = "send ok")
        )
        val router = DefaultOperatorToolExecutor(
            mapOf(
                OperatorToolCatalog.SPAWN_AGENT_NAME to spawnTool,
                OperatorToolCatalog.SEND_MESSAGE_NAME to sendTool
            )
        )

        var result: ChatClientEvent.ToolExecutionResult? = null
        router.execute(
            toolCallId = 1L,
            toolName = OperatorToolCatalog.SPAWN_AGENT_NAME,
            payload = "{}",
            clientEvents = { result = it }
        )

        assertEquals("spawn ok", result?.output)
        assertEquals(false, result?.isError)
        // Only the spawn tool handled the call, with the router forwarding the original args.
        assertEquals(1L, spawnTool.toolCallId)
        assertEquals("{}", spawnTool.payload)
        assertNull(sendTool.toolCallId, "send tool must not be consulted for a spawn call")
    }

    @Test
    fun `routes send_message calls to the send tool and relays its result`() = runTest {
        val spawnTool = RecordingTool(
            ChatClientEvent.ToolExecutionResult(toolCallId = 3L, output = "spawn ok")
        )
        val sendTool = RecordingTool(
            ChatClientEvent.ToolExecutionResult(toolCallId = 3L, output = "Message sent successfully.")
        )
        val router = DefaultOperatorToolExecutor(
            mapOf(
                OperatorToolCatalog.SPAWN_AGENT_NAME to spawnTool,
                OperatorToolCatalog.SEND_MESSAGE_NAME to sendTool
            )
        )

        var result: ChatClientEvent.ToolExecutionResult? = null
        router.execute(
            toolCallId = 3L,
            toolName = OperatorToolCatalog.SEND_MESSAGE_NAME,
            payload = "{}",
            clientEvents = { result = it }
        )

        assertEquals("Message sent successfully.", result?.output)
        assertEquals(false, result?.isError)
        // Only the send tool handled the call, with the router forwarding the original args.
        assertEquals(3L, sendTool.toolCallId)
        assertEquals("{}", sendTool.payload)
        assertNull(spawnTool.toolCallId, "spawn tool must not be consulted for a send_message call")
    }

    @Test
    fun `unknown tool name emits a tool error without delegating`() = runTest {
        val spawnTool = mockk<OperatorTool>()
        val sendTool = mockk<OperatorTool>()
        val router = DefaultOperatorToolExecutor(
            mapOf(
                OperatorToolCatalog.SPAWN_AGENT_NAME to spawnTool,
                OperatorToolCatalog.SEND_MESSAGE_NAME to sendTool
            )
        )

        var result: ChatClientEvent.ToolExecutionResult? = null
        router.execute(
            toolCallId = 1L,
            toolName = "future_tool",
            payload = "{}",
            clientEvents = { result = it }
        )

        assertEquals(1L, result?.toolCallId)
        assertEquals(true, result?.isError)
        assertTrue(result?.errorMessage?.contains("future_tool") == true)
        // Neither tool receives a name it does not handle.
        coVerify(exactly = 0) { spawnTool.execute(any(), any(), any()) }
        coVerify(exactly = 0) { sendTool.execute(any(), any(), any()) }
    }
}