package eu.torvian.chatbot.worker.protocol.interaction

import arrow.core.left
import arrow.core.getOrElse
import arrow.core.right
import arrow.core.Either
import eu.torvian.chatbot.common.models.api.worker.protocol.constants.WorkerCommandResultStatuses
import eu.torvian.chatbot.common.models.api.worker.protocol.codec.decodeProtocolPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.constants.WorkerProtocolCommandTypes
import eu.torvian.chatbot.common.models.api.worker.protocol.constants.WorkerProtocolMessageTypes
import eu.torvian.chatbot.common.models.api.worker.protocol.constants.WorkerProtocolRejectionReasons
import eu.torvian.chatbot.common.models.api.worker.protocol.core.WorkerProtocolMessage
import eu.torvian.chatbot.common.models.api.worker.protocol.mapping.toWorkerCommandRequestPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.mapping.toWorkerMcpServerStartErrorResultData
import eu.torvian.chatbot.common.models.api.worker.protocol.mapping.toWorkerMcpServerRefreshToolsResultData
import eu.torvian.chatbot.common.models.api.worker.protocol.mapping.toWorkerMcpServerStartResultData
import eu.torvian.chatbot.common.models.api.worker.protocol.mapping.toWorkerMcpServerStopResultData
import eu.torvian.chatbot.common.models.api.worker.protocol.mapping.toWorkerMcpServerTestConnectionResultData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCommandRejectedPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCommandRequestPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCommandResultPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerControlErrorResultData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerRefreshToolsCommandData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerRefreshToolsResultData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerStartCommandData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerStartResultData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerStopCommandData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerStopResultData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerTestConnectionCommandData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerTestConnectionResultData
import eu.torvian.chatbot.worker.mcp.DummyWorkerMcpServerControlCommandExecutor
import eu.torvian.chatbot.worker.mcp.WorkerMcpServerControlCommandExecutor
import eu.torvian.chatbot.worker.protocol.ids.WorkerMessageIdProvider
import eu.torvian.chatbot.worker.protocol.transport.WorkerOutboundMessageEmitter
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for [WorkerMcpServerControlInteraction].
 */
class WorkerMcpServerControlCommandProcessorTest {
    /**
     * Verifies that every supported command emits `command.accepted` followed by `command.result`.
     */
    @Test
    fun `supported commands emit accepted then result`() = kotlinx.coroutines.test.runTest {
        val scenarios = listOf(
            SupportedCommandScenario(
                commandType = WorkerProtocolCommandTypes.MCP_SERVER_START,
                requestPayload = WorkerMcpServerStartCommandData(serverId = 10L)
                    .toWorkerCommandRequestPayload()
                    .orError(),
                verifyResult = { resultPayload, commandType ->
                    val data = resultPayload.toWorkerMcpServerStartResultData(commandType).orError()
                    assertEquals(10L, data.serverId)
                }
            ),
            SupportedCommandScenario(
                commandType = WorkerProtocolCommandTypes.MCP_SERVER_STOP,
                requestPayload = WorkerMcpServerStopCommandData(serverId = 11L)
                    .toWorkerCommandRequestPayload()
                    .orError(),
                verifyResult = { resultPayload, commandType ->
                    val data = resultPayload.toWorkerMcpServerStopResultData(commandType).orError()
                    assertEquals(11L, data.serverId)
                }
            ),
            SupportedCommandScenario(
                commandType = WorkerProtocolCommandTypes.MCP_SERVER_TEST_CONNECTION,
                requestPayload = WorkerMcpServerTestConnectionCommandData(serverId = 12L)
                    .toWorkerCommandRequestPayload()
                    .orError(),
                verifyResult = { resultPayload, commandType ->
                    val data = resultPayload.toWorkerMcpServerTestConnectionResultData(commandType).orError()
                    assertEquals(12L, data.serverId)
                    assertEquals(true, data.success)
                }
            ),
            SupportedCommandScenario(
                commandType = WorkerProtocolCommandTypes.MCP_SERVER_REFRESH_TOOLS,
                requestPayload = WorkerMcpServerRefreshToolsCommandData(serverId = 13L)
                    .toWorkerCommandRequestPayload()
                    .orError(),
                verifyResult = { resultPayload, commandType ->
                    val data = resultPayload.toWorkerMcpServerRefreshToolsResultData(commandType).orError()
                    assertEquals(13L, data.serverId)
                }
            )
        )

        scenarios.forEachIndexed { index, scenario ->
            val emitter = RecordingEmitter()
            val interaction = buildInteraction(
                interactionId = "int-$index",
                requestPayload = scenario.requestPayload,
                emitter = emitter
            )

            interaction.start()

            assertEquals(2, emitter.messages.size)
            assertEquals(WorkerProtocolMessageTypes.COMMAND_ACCEPTED, emitter.messages[0].type)
            assertEquals(WorkerProtocolMessageTypes.COMMAND_RESULT, emitter.messages[1].type)

            val resultPayload = decodeProtocolPayload<WorkerCommandResultPayload>(
                payload = emitter.messages[1].payload!!,
                targetType = "WorkerCommandResultPayload"
            ).orError()

            scenario.verifyResult(resultPayload, scenario.commandType)
        }
    }

    /**
     * Verifies malformed payloads are rejected for each supported command type.
     */
    @Test
    fun `malformed payloads are rejected for every command`() = kotlinx.coroutines.test.runTest {
        val malformedRequests = listOf(
            WorkerCommandRequestPayload(
                commandType = WorkerProtocolCommandTypes.MCP_SERVER_START,
                data = malformedServerIdPayload()
            ),
            WorkerCommandRequestPayload(
                commandType = WorkerProtocolCommandTypes.MCP_SERVER_STOP,
                data = malformedServerIdPayload()
            ),
            WorkerCommandRequestPayload(
                commandType = WorkerProtocolCommandTypes.MCP_SERVER_TEST_CONNECTION,
                data = malformedServerIdPayload()
            ),
            WorkerCommandRequestPayload(
                commandType = WorkerProtocolCommandTypes.MCP_SERVER_REFRESH_TOOLS,
                data = malformedServerIdPayload()
            )
        )

        malformedRequests.forEachIndexed { index, request ->
            val emitter = RecordingEmitter()
            val interaction = buildInteraction(
                interactionId = "reject-$index",
                requestPayload = request,
                emitter = emitter
            )

            interaction.start()

            assertEquals(1, emitter.messages.size)
            assertEquals(WorkerProtocolMessageTypes.COMMAND_REJECTED, emitter.messages.single().type)

            val rejected = decodeProtocolPayload<WorkerCommandRejectedPayload>(
                payload = emitter.messages.single().payload!!,
                targetType = "WorkerCommandRejectedPayload"
            ).orError()
            assertEquals(request.commandType, rejected.commandType)
            assertEquals(WorkerProtocolRejectionReasons.INVALID_COMMAND_PAYLOAD, rejected.reasonCode)
            assertNotNull(rejected.details)
        }
    }

    /**
     * Verifies the dummy test-connection result is deterministic.
     */
    @Test
    fun `test connection emits deterministic dummy result`() = kotlinx.coroutines.test.runTest {
        val emitter = RecordingEmitter()
        val interaction = buildInteraction(
            interactionId = "int-test-connection",
            requestPayload = WorkerMcpServerTestConnectionCommandData(serverId = 44L)
                .toWorkerCommandRequestPayload()
                .orError(),
            emitter = emitter
        )

        interaction.start()

        val resultPayload = decodeProtocolPayload<WorkerCommandResultPayload>(
            payload = emitter.messages[1].payload!!,
            targetType = "WorkerCommandResultPayload"
        ).orError()
        val resultData = resultPayload
            .toWorkerMcpServerTestConnectionResultData(WorkerProtocolCommandTypes.MCP_SERVER_TEST_CONNECTION)
            .orError()

        assertEquals(44L, resultData.serverId)
        assertEquals(true, resultData.success)
        assertEquals(DummyWorkerMcpServerControlCommandExecutor.DUMMY_DISCOVERED_TOOL_COUNT, resultData.discoveredToolCount)
        assertEquals(DummyWorkerMcpServerControlCommandExecutor.DUMMY_TEST_CONNECTION_MESSAGE, resultData.message)
    }

    /**
     * Verifies the dummy refresh-tools result always carries an empty diff.
     */
    @Test
    fun `refresh tools emits deterministic empty diff`() = kotlinx.coroutines.test.runTest {
        val emitter = RecordingEmitter()
        val interaction = buildInteraction(
            interactionId = "int-refresh-tools",
            requestPayload = WorkerMcpServerRefreshToolsCommandData(serverId = 45L)
                .toWorkerCommandRequestPayload()
                .orError(),
            emitter = emitter
        )

        interaction.start()

        val resultPayload = decodeProtocolPayload<WorkerCommandResultPayload>(
            payload = emitter.messages[1].payload!!,
            targetType = "WorkerCommandResultPayload"
        ).orError()
        val resultData = resultPayload
            .toWorkerMcpServerRefreshToolsResultData(WorkerProtocolCommandTypes.MCP_SERVER_REFRESH_TOOLS)
            .orError()

        assertEquals(45L, resultData.serverId)
        assertTrue(resultData.addedTools.isEmpty())
        assertTrue(resultData.updatedTools.isEmpty())
        assertTrue(resultData.deletedTools.isEmpty())
    }

    /**
     * Verifies executor failures are encoded as `command.result` payloads with `error` status.
     */
    @Test
    fun `executor failure emits command result with typed error data`() = kotlinx.coroutines.test.runTest {
        val emitter = RecordingEmitter()
        val interaction = buildInteraction(
            interactionId = "int-start-error",
            requestPayload = WorkerMcpServerStartCommandData(serverId = 77L)
                .toWorkerCommandRequestPayload()
                .orError(),
            emitter = emitter,
            executor = FailingStartExecutor()
        )

        interaction.start()

        assertEquals(2, emitter.messages.size)
        assertEquals(WorkerProtocolMessageTypes.COMMAND_ACCEPTED, emitter.messages[0].type)
        assertEquals(WorkerProtocolMessageTypes.COMMAND_RESULT, emitter.messages[1].type)

        val resultPayload = decodeProtocolPayload<WorkerCommandResultPayload>(
            payload = emitter.messages[1].payload!!,
            targetType = "WorkerCommandResultPayload"
        ).orError()
        assertEquals(WorkerCommandResultStatuses.ERROR, resultPayload.status)

        val errorData = resultPayload
            .toWorkerMcpServerStartErrorResultData(WorkerProtocolCommandTypes.MCP_SERVER_START)
            .orError()
        assertEquals(77L, errorData.serverId)
        assertEquals("DUMMY_START_FAILED", errorData.code)
        assertEquals("Dummy start failure", errorData.message)
        assertEquals("Deterministic failure for tests", errorData.details)
    }

    /**
     * Builds a control-command interaction configured with the deterministic dummy executor.
     *
     * @param interactionId Logical interaction identifier carried by the envelope.
     * @param requestPayload Request payload used by the interaction.
     * @param emitter Outbound message emitter used for assertions.
     * @param executor Command executor under test.
     * @return Configured interaction instance.
     */
    private fun buildInteraction(
        interactionId: String,
        requestPayload: WorkerCommandRequestPayload,
        emitter: RecordingEmitter,
        executor: WorkerMcpServerControlCommandExecutor = DummyWorkerMcpServerControlCommandExecutor()
    ): WorkerMcpServerControlInteraction {
        return WorkerMcpServerControlInteraction(
            envelope = WorkerProtocolMessage(
                id = "in-$interactionId",
                type = WorkerProtocolMessageTypes.COMMAND_REQUEST,
                interactionId = interactionId,
                payload = JsonObject(emptyMap())
            ),
            requestPayload = requestPayload,
            executor = executor,
            emitter = emitter,
            messageIdProvider = SequenceMessageIdProvider()
        )
    }

    /**
     * Creates malformed command data where `serverId` has the wrong primitive type.
     *
     * @return Invalid request payload data used to validate rejection behavior.
     */
    private fun malformedServerIdPayload(): JsonObject = buildJsonObject {
        put("serverId", "not-a-number")
    }

    /**
     * Fixture for a supported command-type lifecycle scenario.
     *
     * @property commandType Command type expected for result decoding.
     * @property requestPayload Encoded request payload sent into the interaction.
     * @property verifyResult Assertion callback for decoded command-result payloads.
     */
    private data class SupportedCommandScenario(
        val commandType: String,
        val requestPayload: WorkerCommandRequestPayload,
        val verifyResult: (WorkerCommandResultPayload, String) -> Unit
    )

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

    /**
     * Executor fixture that returns a deterministic `Left` for start commands.
     */
    private class FailingStartExecutor : WorkerMcpServerControlCommandExecutor {
        /**
         * @param request Typed start-command input data.
         * @return Deterministic start failure payload.
         */
        override suspend fun startServer(
            request: WorkerMcpServerStartCommandData
        ): Either<WorkerMcpServerControlErrorResultData, WorkerMcpServerStartResultData> =
            WorkerMcpServerControlErrorResultData(
                serverId = request.serverId,
                code = "DUMMY_START_FAILED",
                message = "Dummy start failure",
                details = "Deterministic failure for tests"
            ).left()

        /**
         * @param request Typed stop-command input data.
         * @return Deterministic successful stop result.
         */
        override suspend fun stopServer(
            request: WorkerMcpServerStopCommandData
        ): Either<WorkerMcpServerControlErrorResultData, WorkerMcpServerStopResultData> =
            WorkerMcpServerStopResultData(serverId = request.serverId).right()

        /**
         * @param request Typed test-connection command input data.
         * @return Deterministic successful test-connection result.
         */
        override suspend fun testConnection(
            request: WorkerMcpServerTestConnectionCommandData
        ): Either<WorkerMcpServerControlErrorResultData, WorkerMcpServerTestConnectionResultData> =
            WorkerMcpServerTestConnectionResultData(
                serverId = request.serverId,
                success = true,
                discoveredToolCount = 0,
                message = "ok"
            ).right()

        /**
         * @param request Typed refresh-tools command input data.
         * @return Deterministic successful empty refresh diff result.
         */
        override suspend fun refreshTools(
            request: WorkerMcpServerRefreshToolsCommandData
        ): Either<WorkerMcpServerControlErrorResultData, WorkerMcpServerRefreshToolsResultData> =
            WorkerMcpServerRefreshToolsResultData(
                serverId = request.serverId,
                addedTools = emptyList(),
                updatedTools = emptyList(),
                deletedTools = emptyList()
            ).right()
    }

    /**
     * Returns the right value from an `Either` fixture or fails fast.
     *
     * @receiver Either value under assertion.
     * @return Right value.
     */
    private fun <L, R> Either<L, R>.orError(): R = getOrElse {
        error("Unexpected mapping error in test fixture: $it")
    }
}

