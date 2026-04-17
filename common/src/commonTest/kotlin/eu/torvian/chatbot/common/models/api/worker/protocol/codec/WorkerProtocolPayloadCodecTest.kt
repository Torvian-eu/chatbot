package eu.torvian.chatbot.common.models.api.worker.protocol.codec

import arrow.core.getOrElse
import eu.torvian.chatbot.common.models.api.worker.protocol.constants.WorkerProtocolCommandMessageKinds
import eu.torvian.chatbot.common.models.api.worker.protocol.constants.WorkerProtocolCommandTypes
import eu.torvian.chatbot.common.models.api.worker.protocol.constants.WorkerProtocolRejectionReasons
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCommandMessagePayload
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCommandRejectedPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCommandRequestPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerSessionHelloPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerSessionWelcomePayload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Verifies the shared worker payload codec on representative payload DTOs.
 */
class WorkerProtocolPayloadCodecTest {
    /**
     * Ensures a session-hello payload survives an encode/decode round-trip.
     */
    @Test
    fun `session hello payload round trips through codec`() {
        val payload = WorkerSessionHelloPayload(
            workerUid = "worker-1",
            capabilities = listOf("mcp.tool.call"),
            supportedProtocolVersions = listOf(1),
            workerVersion = "1.0.0"
        )

        val encoded = encodeProtocolPayload(payload, "WorkerSessionHelloPayload")
            .getOrElse { error("Failed to encode session hello payload: $it") }
        val decoded = decodeProtocolPayload<WorkerSessionHelloPayload>(encoded, "WorkerSessionHelloPayload")
            .getOrElse { error("Failed to decode session hello payload: $it") }

        assertEquals(payload, decoded)
    }

    /**
     * Ensures a session-welcome payload survives an encode/decode round-trip.
     */
    @Test
    fun `session welcome payload round trips through codec`() {
        val payload = WorkerSessionWelcomePayload(
            workerUid = "worker-1",
            selectedProtocolVersion = 1,
            acceptedCapabilities = listOf("mcp.tool.call")
        )

        val encoded = encodeProtocolPayload(payload, "WorkerSessionWelcomePayload")
            .getOrElse { error("Failed to encode session welcome payload: $it") }
        val decoded = decodeProtocolPayload<WorkerSessionWelcomePayload>(encoded, "WorkerSessionWelcomePayload")
            .getOrElse { error("Failed to decode session welcome payload: $it") }

        assertEquals(payload, decoded)
    }

    /**
     * Ensures a command-request payload survives an encode/decode round-trip.
     */
    @Test
    fun `command request payload round trips through codec`() {
        val payload = WorkerCommandRequestPayload(
            commandType = WorkerProtocolCommandTypes.TOOL_CALL,
            data = buildJsonObject {
                put("toolCallId", 42)
                put("serverId", 7)
                put("toolName", "searchDocs")
                put("inputJson", "{\"query\":\"ktor\"}")
            }
        )

        val encoded = encodeProtocolPayload(payload, "WorkerCommandRequestPayload")
            .getOrElse { error("Failed to encode request payload: $it") }
        val decoded = decodeProtocolPayload<WorkerCommandRequestPayload>(encoded, "WorkerCommandRequestPayload")
            .getOrElse { error("Failed to decode request payload: $it") }

        assertEquals(payload, decoded)
    }

    /**
     * Ensures a rejected-command payload survives an encode/decode round-trip.
     */
    @Test
    fun `command rejected payload round trips through codec`() {
        val payload = WorkerCommandRejectedPayload(
            commandType = null,
            reasonCode = WorkerProtocolRejectionReasons.MISSING_PAYLOAD,
            message = "command.request payload is missing",
            details = buildJsonObject {
                put("messageType", "command.request")
            }
        )

        val encoded = encodeProtocolPayload(payload, "WorkerCommandRejectedPayload")
            .getOrElse { error("Failed to encode rejected payload: $it") }
        val decoded = decodeProtocolPayload<WorkerCommandRejectedPayload>(encoded, "WorkerCommandRejectedPayload")
            .getOrElse { error("Failed to decode rejected payload: $it") }

        assertEquals(payload, decoded)
    }

    /**
     * Ensures a command-message payload survives an encode/decode round-trip.
     */
    @Test
    fun `command message payload round trips through codec`() {
        val payload = WorkerCommandMessagePayload(
            messageKind = WorkerProtocolCommandMessageKinds.PROCEED,
            data = buildJsonObject {
                put("attempt", 1)
            }
        )

        val encoded = encodeProtocolPayload(payload, "WorkerCommandMessagePayload")
            .getOrElse { error("Failed to encode command message payload: $it") }
        val decoded = decodeProtocolPayload<WorkerCommandMessagePayload>(encoded, "WorkerCommandMessagePayload")
            .getOrElse { error("Failed to decode command message payload: $it") }

        assertEquals(payload, decoded)
    }
}
