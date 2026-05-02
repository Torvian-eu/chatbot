package eu.torvian.chatbot.common.models.api.worker.protocol.builders

import arrow.core.getOrElse
import eu.torvian.chatbot.common.models.api.worker.protocol.codec.decodeProtocolPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.constants.WorkerProtocolMessageTypes
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerSessionHelloPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerSessionWelcomePayload
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies helper builders in WorkerProtocolMessageBuilders.kt.
 */
class WorkerProtocolMessagesTest {
    /**
     * Ensures `session.hello` builder uses the expected envelope metadata and payload encoding.
     */
    @Test
    fun `session hello builder produces protocol envelope`() {
        val payload = WorkerSessionHelloPayload(
            workerUid = "worker-1",
            capabilities = listOf("mcp.tool.call"),
            supportedProtocolVersions = listOf(1),
            workerVersion = "1.0.0"
        )

        val message = sessionHello(
            id = "msg-1",
            interactionId = "int-1",
            payload = payload
        )

        assertEquals("msg-1", message.id)
        assertEquals(WorkerProtocolMessageTypes.SESSION_HELLO, message.type)
        assertEquals(null, message.replyTo)
        assertEquals("int-1", message.interactionId)
        val decodedPayload = decodeProtocolPayload<WorkerSessionHelloPayload>(
            payload = message.payload!!,
            targetType = "WorkerSessionHelloPayload"
        ).getOrElse { error("Expected hello payload to decode: $it") }
        assertEquals(payload, decodedPayload)
    }

    /**
     * Ensures `session.welcome` builder uses the expected envelope metadata and payload encoding.
     */
    @Test
    fun `session welcome builder produces protocol envelope`() {
        val payload = WorkerSessionWelcomePayload(
            workerUid = "worker-1",
            selectedProtocolVersion = 1,
            acceptedCapabilities = listOf("mcp.tool.call")
        )

        val message = sessionWelcome(
            id = "msg-2",
            replyTo = "msg-1",
            interactionId = "int-1",
            payload = payload
        )

        assertEquals("msg-2", message.id)
        assertEquals(WorkerProtocolMessageTypes.SESSION_WELCOME, message.type)
        assertEquals("msg-1", message.replyTo)
        assertEquals("int-1", message.interactionId)
        val decodedPayload = decodeProtocolPayload<WorkerSessionWelcomePayload>(
            payload = message.payload!!,
            targetType = "WorkerSessionWelcomePayload"
        ).getOrElse { error("Expected welcome payload to decode: $it") }
        assertEquals(payload, decodedPayload)
    }
}
