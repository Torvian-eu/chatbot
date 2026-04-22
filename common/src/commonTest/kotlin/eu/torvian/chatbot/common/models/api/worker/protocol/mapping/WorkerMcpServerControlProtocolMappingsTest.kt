package eu.torvian.chatbot.common.models.api.worker.protocol.mapping

import arrow.core.getOrElse
import eu.torvian.chatbot.common.models.api.worker.protocol.constants.WorkerCommandResultStatuses
import eu.torvian.chatbot.common.models.api.worker.protocol.constants.WorkerProtocolCommandTypes
import eu.torvian.chatbot.common.models.api.mcp.LocalMcpServerRuntimeStateDto
import eu.torvian.chatbot.common.models.api.mcp.LocalMcpServerRuntimeStatusDto
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPServerDto
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCommandRequestPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerControlErrorResultData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpDiscoveredToolData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerCreateCommandData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerCreateResultData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerDeleteCommandData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerDeleteResultData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerDiscoverToolsCommandData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerDiscoverToolsResultData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerGetRuntimeStatusCommandData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerGetRuntimeStatusResultData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerListRuntimeStatusesCommandData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerListRuntimeStatusesResultData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerStartCommandData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerStopCommandData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerTestConnectionCommandData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerTestConnectionResultData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerUpdateCommandData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerUpdateResultData
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Verifies MCP server-control specific worker protocol mapping helpers.
 */
class WorkerMcpServerControlProtocolMappingsTest {
    /**
     * Ensures start request data maps to and from command-request payloads.
     */
    @Test
    fun `start request data encodes and decodes`() {
        val request = WorkerMcpServerStartCommandData(serverId = 11L)

        val payload = request.toWorkerCommandRequestPayload()
            .getOrElse { error("Expected start request mapping success: $it") }

        assertEquals(WorkerProtocolCommandTypes.MCP_SERVER_START, payload.commandType)
        val decoded = payload.toWorkerMcpServerStartCommandData()
            .getOrElse { error("Expected start request reverse mapping success: $it") }
        assertEquals(request, decoded)
    }

    /**
     * Ensures stop request data maps to and from command-request payloads.
     */
    @Test
    fun `stop request data encodes and decodes`() {
        val request = WorkerMcpServerStopCommandData(serverId = 22L)

        val payload = request.toWorkerCommandRequestPayload()
            .getOrElse { error("Expected stop request mapping success: $it") }

        assertEquals(WorkerProtocolCommandTypes.MCP_SERVER_STOP, payload.commandType)
        val decoded = payload.toWorkerMcpServerStopCommandData()
            .getOrElse { error("Expected stop request reverse mapping success: $it") }
        assertEquals(request, decoded)
    }

    /**
     * Ensures test-connection request and result data map correctly.
     */
    @Test
    fun `test connection request and result data encode and decode`() {
        val request = WorkerMcpServerTestConnectionCommandData(serverId = 33L)

        val requestPayload = request.toWorkerCommandRequestPayload()
            .getOrElse { error("Expected test-connection request mapping success: $it") }

        assertEquals(WorkerProtocolCommandTypes.MCP_SERVER_TEST_CONNECTION, requestPayload.commandType)
        val decodedRequest = requestPayload.toWorkerMcpServerTestConnectionCommandData()
            .getOrElse { error("Expected test-connection request reverse mapping success: $it") }
        assertEquals(request, decodedRequest)

        val result = WorkerMcpServerTestConnectionResultData(
            serverId = 33L,
            success = true,
            discoveredToolCount = 5,
            message = "Connected"
        )
        val resultPayload = result.toWorkerCommandResultPayload()
            .getOrElse { error("Expected test-connection result mapping success: $it") }

        val decodedResult = resultPayload.toWorkerMcpServerTestConnectionResultData(
            commandType = WorkerProtocolCommandTypes.MCP_SERVER_TEST_CONNECTION
        ).getOrElse { error("Expected test-connection result reverse mapping success: $it") }
        assertEquals(result, decodedResult)
    }

    /**
     * Ensures discover-tools request and result data map correctly.
     */
    @Test
    fun `discover tools request and result data encode and decode`() {
        val request = WorkerMcpServerDiscoverToolsCommandData(serverId = 44L)

        val requestPayload = request.toWorkerCommandRequestPayload()
            .getOrElse { error("Expected discover-tools request mapping success: $it") }

        assertEquals(WorkerProtocolCommandTypes.MCP_SERVER_DISCOVER_TOOLS, requestPayload.commandType)
        val decodedRequest = requestPayload.toWorkerMcpServerDiscoverToolsCommandData()
            .getOrElse { error("Expected discover-tools request reverse mapping success: $it") }
        assertEquals(request, decodedRequest)

        val result = WorkerMcpServerDiscoverToolsResultData(
            serverId = 44L,
            tools = listOf(
                WorkerMcpDiscoveredToolData(
                    name = "search_files",
                    description = "Searches local files",
                    inputSchema = buildJsonObject { put("type", "object") },
                    outputSchema = null
                )
            )
        )
        val resultPayload = result.toWorkerCommandResultPayload()
            .getOrElse { error("Expected discover-tools result mapping success: $it") }

        val decodedResult = resultPayload.toWorkerMcpServerDiscoverToolsResultData(
            commandType = WorkerProtocolCommandTypes.MCP_SERVER_DISCOVER_TOOLS
        ).getOrElse { error("Expected discover-tools result reverse mapping success: $it") }
        assertEquals(result, decodedResult)
    }

    /**
     * Ensures MCP server-control error result payloads round-trip with `error` status.
     */
    @Test
    fun `error result data encodes and decodes for start command`() {
        val errorResult = WorkerMcpServerControlErrorResultData(
            serverId = 55L,
            code = "START_FAILED",
            message = "Runtime failed to spawn process",
            details = "Executable not found"
        )

        val payload = errorResult.toWorkerCommandResultPayload()
            .getOrElse { error("Expected error result mapping success: $it") }

        assertEquals(WorkerCommandResultStatuses.ERROR, payload.status)
        val decoded = payload.toWorkerMcpServerStartErrorResultData(
            commandType = WorkerProtocolCommandTypes.MCP_SERVER_START
        ).getOrElse { error("Expected error result reverse mapping success: $it") }
        assertEquals(errorResult, decoded)
    }

    /**
     * Ensures decoding fails with a logical error when command type does not match.
     */
    @Test
    fun `wrong command type returns mapping error`() {
        val payload = WorkerCommandRequestPayload(
            commandType = WorkerProtocolCommandTypes.TOOL_CALL,
            data = buildJsonObject { put("serverId", 9L) }
        )

        val error = payload.toWorkerMcpServerStartCommandData().fold(
            ifLeft = { it },
            ifRight = { error("Expected command-type validation failure") }
        )

        val invalidCommandType = assertIs<WorkerMcpRuntimeCommandProtocolMappingError.InvalidCommandType>(error)
        assertEquals(WorkerProtocolCommandTypes.MCP_SERVER_START, invalidCommandType.expected)
        assertEquals(WorkerProtocolCommandTypes.TOOL_CALL, invalidCommandType.actual)
    }

    /**
     * Ensures malformed payload JSON is returned as a mapping error.
     */
    @Test
    fun `malformed payload returns serialization mapping error`() {
        val payload = WorkerCommandRequestPayload(
            commandType = WorkerProtocolCommandTypes.MCP_SERVER_STOP,
            data = buildJsonObject { put("unexpected", "shape") }
        )

        val error = payload.toWorkerMcpServerStopCommandData().fold(
            ifLeft = { it },
            ifRight = { error("Expected malformed payload decoding to fail") }
        )

        assertTrue(error is WorkerMcpRuntimeCommandProtocolMappingError.SerializationFailed)
    }

    /**
     * Ensures get-runtime-status request and result data map correctly.
     */
    @Test
    fun `get runtime status request and result data encode and decode`() {
        val now = Clock.System.now()
        val request = WorkerMcpServerGetRuntimeStatusCommandData(serverId = 66L)

        val requestPayload = request.toWorkerCommandRequestPayload()
            .getOrElse { error("Expected get-runtime-status request mapping success: $it") }

        assertEquals(WorkerProtocolCommandTypes.MCP_SERVER_GET_RUNTIME_STATUS, requestPayload.commandType)
        val decodedRequest = requestPayload.toWorkerMcpServerGetRuntimeStatusCommandData()
            .getOrElse { error("Expected get-runtime-status request reverse mapping success: $it") }
        assertEquals(request, decodedRequest)

        val result = WorkerMcpServerGetRuntimeStatusResultData(
            status = LocalMcpServerRuntimeStatusDto(
                serverId = 66L,
                state = LocalMcpServerRuntimeStateDto.RUNNING,
                connectedAt = now,
                lastActivityAt = now
            )
        )
        val resultPayload = result.toWorkerCommandResultPayload()
            .getOrElse { error("Expected get-runtime-status result mapping success: $it") }

        val decodedResult = resultPayload.toWorkerMcpServerGetRuntimeStatusResultData(
            commandType = WorkerProtocolCommandTypes.MCP_SERVER_GET_RUNTIME_STATUS
        ).getOrElse { error("Expected get-runtime-status result reverse mapping success: $it") }
        assertEquals(result, decodedResult)
    }

    /**
     * Ensures list-runtime-statuses request and result data map correctly.
     */
    @Test
    fun `list runtime statuses request and result data encode and decode`() {
        val request = WorkerMcpServerListRuntimeStatusesCommandData

        val requestPayload = request.toWorkerCommandRequestPayload()
            .getOrElse { error("Expected list-runtime-statuses request mapping success: $it") }

        assertEquals(WorkerProtocolCommandTypes.MCP_SERVER_LIST_RUNTIME_STATUSES, requestPayload.commandType)
        requestPayload.toWorkerMcpServerListRuntimeStatusesCommandData()
            .getOrElse { error("Expected list-runtime-statuses request reverse mapping success: $it") }

        val result = WorkerMcpServerListRuntimeStatusesResultData(
            statuses = listOf(
                LocalMcpServerRuntimeStatusDto(
                    serverId = 77L,
                    state = LocalMcpServerRuntimeStateDto.STOPPED,
                    errorMessage = "worker disconnected"
                )
            )
        )
        val resultPayload = result.toWorkerCommandResultPayload()
            .getOrElse { error("Expected list-runtime-statuses result mapping success: $it") }

        val decodedResult = resultPayload.toWorkerMcpServerListRuntimeStatusesResultData(
            commandType = WorkerProtocolCommandTypes.MCP_SERVER_LIST_RUNTIME_STATUSES
        ).getOrElse { error("Expected list-runtime-statuses result reverse mapping success: $it") }
        assertEquals(result, decodedResult)
    }

    /**
     * Ensures create/update/delete cache-sync request and result payloads map correctly.
     */
    @Test
    fun `cache sync create update and delete payloads encode and decode`() {
        val server = testServer(serverId = 88L)

        val createRequestPayload = WorkerMcpServerCreateCommandData(server = server)
            .toWorkerCommandRequestPayload()
            .getOrElse { error("Expected create request mapping success: $it") }
        assertEquals(WorkerProtocolCommandTypes.MCP_SERVER_CREATE, createRequestPayload.commandType)
        assertEquals(server, createRequestPayload.toWorkerMcpServerCreateCommandData().getOrElse { error(it) }.server)

        val updateRequestPayload = WorkerMcpServerUpdateCommandData(server = server.copy(name = "filesystem-v2"))
            .toWorkerCommandRequestPayload()
            .getOrElse { error("Expected update request mapping success: $it") }
        assertEquals(WorkerProtocolCommandTypes.MCP_SERVER_UPDATE, updateRequestPayload.commandType)
        assertEquals("filesystem-v2", updateRequestPayload.toWorkerMcpServerUpdateCommandData().getOrElse { error(it) }.server.name)

        val deleteRequestPayload = WorkerMcpServerDeleteCommandData(serverId = server.id)
            .toWorkerCommandRequestPayload()
            .getOrElse { error("Expected delete request mapping success: $it") }
        assertEquals(WorkerProtocolCommandTypes.MCP_SERVER_DELETE, deleteRequestPayload.commandType)
        assertEquals(server.id, deleteRequestPayload.toWorkerMcpServerDeleteCommandData().getOrElse { error(it) }.serverId)

        val createResult = WorkerMcpServerCreateResultData(serverId = server.id)
        val createResultPayload = createResult.toWorkerCommandResultPayload()
            .getOrElse { error("Expected create result mapping success: $it") }
        assertEquals(
            createResult,
            createResultPayload.toWorkerMcpServerCreateResultData(WorkerProtocolCommandTypes.MCP_SERVER_CREATE)
                .getOrElse { error("Expected create result reverse mapping success: $it") }
        )

        val updateResult = WorkerMcpServerUpdateResultData(serverId = server.id)
        val updateResultPayload = updateResult.toWorkerCommandResultPayload()
            .getOrElse { error("Expected update result mapping success: $it") }
        assertEquals(
            updateResult,
            updateResultPayload.toWorkerMcpServerUpdateResultData(WorkerProtocolCommandTypes.MCP_SERVER_UPDATE)
                .getOrElse { error("Expected update result reverse mapping success: $it") }
        )

        val deleteResult = WorkerMcpServerDeleteResultData(serverId = server.id)
        val deleteResultPayload = deleteResult.toWorkerCommandResultPayload()
            .getOrElse { error("Expected delete result mapping success: $it") }
        assertEquals(
            deleteResult,
            deleteResultPayload.toWorkerMcpServerDeleteResultData(WorkerProtocolCommandTypes.MCP_SERVER_DELETE)
                .getOrElse { error("Expected delete result reverse mapping success: $it") }
        )
    }

    /**
     * Builds a deterministic Local MCP server DTO fixture for config-sync mapping tests.
     *
     * @param serverId Persisted server identifier used in the fixture.
     * @return Deterministic server DTO.
     */
    private fun testServer(serverId: Long): LocalMCPServerDto = LocalMCPServerDto(
        id = serverId,
        userId = 5L,
        workerId = 7L,
        name = "filesystem",
        command = "npx",
        createdAt = Instant.fromEpochMilliseconds(1_700_000_000_000),
        updatedAt = Instant.fromEpochMilliseconds(1_700_000_100_000)
    )
}


