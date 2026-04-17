package eu.torvian.chatbot.worker.protocol.interaction

import arrow.core.getOrElse
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPToolCallRequest
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPToolCallResult
import eu.torvian.chatbot.common.models.api.worker.protocol.core.WorkerProtocolMessage
import eu.torvian.chatbot.common.models.api.worker.protocol.constants.WorkerProtocolMessageTypes
import eu.torvian.chatbot.common.models.api.worker.protocol.mapping.toWorkerCommandRequestPayload
import eu.torvian.chatbot.worker.mcp.WorkerToolCallExecutor
import eu.torvian.chatbot.worker.protocol.transport.WorkerOutboundMessageEmitter
import eu.torvian.chatbot.worker.protocol.ids.WorkerMessageIdProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Unit tests for [WorkerMcpToolCallInteraction].
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WorkerMcpToolCallCommandProcessorTest {
    /**
     * Verifies that `command.accepted` is emitted before `command.result`.
     */
    @Test
    fun `mcp session emits accepted before result`() = runTest {
        val emitter = RecordingEmitter()
        val executor = BlockingExecutor(
            result = LocalMCPToolCallResult(toolCallId = 900, output = "ok")
        )
        val session = buildSession(
            executor = executor,
            emitter = emitter,
            interactionId = "int-1"
        )

        val job = launch { session.start() }

        executor.started.await()
        assertEquals(1, emitter.messages.size)
        assertEquals(WorkerProtocolMessageTypes.COMMAND_ACCEPTED, emitter.messages[0].type)

        executor.release.complete(Unit)
        runCurrent()
        job.join()

        assertEquals(2, emitter.messages.size)
        assertEquals(WorkerProtocolMessageTypes.COMMAND_RESULT, emitter.messages[1].type)
    }

    /**
     * Verifies that executor failures are propagated to the interaction owner.
     */
    @Test
    fun `mcp session propagates executor failure`() = runTest {
        val emitter = RecordingEmitter()
        val session = buildSession(
            executor = ThrowingExecutor(),
            emitter = emitter,
            interactionId = "int-3"
        )

        assertFailsWith<IllegalStateException> {
            session.start()
        }
    }

    /**
     * Builds an MCP command interaction for tests.
     *
     * @param executor Tool-call executor used by the interaction.
     * @param emitter Outbound emitter used by the interaction.
     * @param interactionId Logical interaction identifier carried by the envelope.
     * @return Configured interaction instance.
     */
    private fun buildSession(
        executor: WorkerToolCallExecutor,
        emitter: RecordingEmitter,
        interactionId: String
    ): WorkerMcpToolCallInteraction {
        val requestPayload = LocalMCPToolCallRequest(
            toolCallId = 900,
            serverId = 4,
            toolName = "searchDocs",
            inputJson = "{\"query\":\"ktor\"}"
        ).toWorkerCommandRequestPayload()
            .getOrElse { error("Failed to build request payload for test: $it") }

        return WorkerMcpToolCallInteraction(
            envelope = WorkerProtocolMessage(
                id = "in-$interactionId",
                type = WorkerProtocolMessageTypes.COMMAND_REQUEST,
                interactionId = interactionId,
                payload = JsonObject(emptyMap())
            ),
            requestPayload = requestPayload,
            toolCallExecutor = executor,
            emitter = emitter,
            messageIdProvider = SequenceMessageIdProvider()
        )
    }

    /**
     * Recording outbound emitter used for assertions.
     */
    private class RecordingEmitter : WorkerOutboundMessageEmitter {
        /**
         * Collected outbound messages in send order.
         */
        val messages: MutableList<WorkerProtocolMessage> = mutableListOf()

        /**
         * @param message Outbound protocol envelope to record.
         */
        override suspend fun emit(message: WorkerProtocolMessage) {
            messages += message
        }
    }

    /**
     * Fixed executor used to make session tests deterministic.
     *
     * @property result Result returned for every execution.
     */
    private class StaticExecutor(
        private val result: LocalMCPToolCallResult
    ) : WorkerToolCallExecutor {
        /**
         * @param request Tool-call request supplied by the session.
         * @return Preconfigured result.
         */
        override suspend fun execute(request: LocalMCPToolCallRequest): LocalMCPToolCallResult {
            return result
        }
    }

    /**
     * Executor that blocks after start observation until explicitly released.
     *
     * @property result Result returned after release.
     */
    private class BlockingExecutor(
        private val result: LocalMCPToolCallResult
    ) : WorkerToolCallExecutor {
        /**
         * Deferred completed when execution starts.
         */
        val started: CompletableDeferred<Unit> = CompletableDeferred()

        /**
         * Deferred that controls when execution may continue.
         */
        val release: CompletableDeferred<Unit> = CompletableDeferred()

        /**
         * @param request Tool-call request supplied by the session.
         * @return Preconfigured result once released.
         */
        override suspend fun execute(request: LocalMCPToolCallRequest): LocalMCPToolCallResult {
            started.complete(Unit)
            release.await()
            return result
        }
    }

    /**
     * Executor that throws to validate session cleanup paths.
     */
    private class ThrowingExecutor : WorkerToolCallExecutor {
        /**
         * @param request Tool-call request supplied by the session.
         * @throws IllegalStateException Always thrown to simulate an unexpected failure.
         */
        override suspend fun execute(request: LocalMCPToolCallRequest): LocalMCPToolCallResult {
            throw IllegalStateException("boom")
        }
    }

    /**
     * Deterministic message-id provider for stable protocol assertions.
     */
    private class SequenceMessageIdProvider : WorkerMessageIdProvider {
        /**
         * Internal counter used to produce stable increasing IDs.
         */
        private var counter: Int = 0

        /**
         * Produces the next deterministic test message ID.
         *
         * @return Stable identifier in the form `msg-N`.
         */
        override fun nextMessageId(): String {
            counter += 1
            return "msg-$counter"
        }
    }
}

