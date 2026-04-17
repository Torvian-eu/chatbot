package eu.torvian.chatbot.worker.protocol.routing

import arrow.core.getOrElse
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCommandRejectedPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCommandRequestPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.constants.WorkerProtocolCommandTypes
import eu.torvian.chatbot.common.models.api.worker.protocol.core.WorkerProtocolMessage
import eu.torvian.chatbot.common.models.api.worker.protocol.constants.WorkerProtocolMessageTypes
import eu.torvian.chatbot.common.models.api.worker.protocol.constants.WorkerProtocolRejectionReasons
import eu.torvian.chatbot.common.models.api.worker.protocol.codec.decodeProtocolPayload
import eu.torvian.chatbot.worker.protocol.transport.WorkerOutboundMessageEmitter
import eu.torvian.chatbot.worker.protocol.factory.WorkerInteractionFactory
import eu.torvian.chatbot.worker.protocol.ids.WorkerMessageIdProvider
import eu.torvian.chatbot.worker.protocol.interaction.WorkerActiveInteraction
import eu.torvian.chatbot.worker.protocol.registry.InMemoryWorkerActiveInteractionRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for [WorkerCommandRequestProcessor].
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WorkerCommandRequestProcessorTest {
    /**
     * Verifies that missing request payloads are rejected immediately.
     */
    @Test
    fun `missing payload is rejected`() = runTest {
        val emitter = RecordingEmitter()
        val registry = InMemoryWorkerActiveInteractionRegistry()
        val processor = WorkerCommandRequestProcessor(
            interactionScope = this,
            interactionFactoriesByCommandType = emptyMap(),
            emitter = emitter,
            registry = registry,
            messageIdProvider = SequenceMessageIdProvider()
        )

        processor.process(
            WorkerProtocolMessage(
                id = "in-1",
                type = WorkerProtocolMessageTypes.COMMAND_REQUEST,
                interactionId = "int-1",
                payload = null
            )
        )

        assertEquals(1, emitter.messages.size)
        val rejected = decodeProtocolPayload<WorkerCommandRejectedPayload>(
            emitter.messages.single().payload!!,
            "WorkerCommandRejectedPayload"
        ).getOrElse { error("Expected rejection payload to decode: $it") }
        assertEquals(WorkerProtocolRejectionReasons.MISSING_PAYLOAD, rejected.reasonCode)
    }

    /**
     * Verifies that duplicate active interaction IDs are rejected.
     */
    @Test
    fun `duplicate interaction id is rejected`() = runTest {
        val emitter = RecordingEmitter()
        val registry = InMemoryWorkerActiveInteractionRegistry()
        val blockingInteractionFactory = BlockingInteractionFactory()
        val processor = WorkerCommandRequestProcessor(
            interactionScope = this,
            interactionFactoriesByCommandType = mapOf(
                WorkerProtocolCommandTypes.MCP_TOOL_CALL to blockingInteractionFactory
            ),
            emitter = emitter,
            registry = registry,
            messageIdProvider = SequenceMessageIdProvider()
        )

        val firstMessage = commandRequestEnvelope(
            inboundMessageId = "in-1",
            interactionId = "int-1",
            commandType = WorkerProtocolCommandTypes.MCP_TOOL_CALL
        )
        val secondMessage = commandRequestEnvelope(
            inboundMessageId = "in-2",
            interactionId = "int-1",
            commandType = WorkerProtocolCommandTypes.MCP_TOOL_CALL
        )

        processor.process(firstMessage)
        runCurrent()
        processor.process(secondMessage)

        val rejected = decodeProtocolPayload<WorkerCommandRejectedPayload>(
            emitter.messages.single().payload!!,
            "WorkerCommandRejectedPayload"
        ).getOrElse { error("Expected rejection payload to decode: $it") }
        assertEquals(WorkerProtocolRejectionReasons.DUPLICATE_INTERACTION_ID, rejected.reasonCode)

        blockingInteractionFactory.allowCompletion.complete(Unit)
        runCurrent()
    }

    /**
     * Verifies that active interactions are launched asynchronously and do not block request handling.
     */
    @Test
    fun `request handling launches interaction asynchronously`() = runTest {
        val emitter = RecordingEmitter()
        val registry = InMemoryWorkerActiveInteractionRegistry()
        val blockingInteractionFactory = BlockingInteractionFactory()
        val processor = WorkerCommandRequestProcessor(
            interactionScope = this,
            interactionFactoriesByCommandType = mapOf(
                WorkerProtocolCommandTypes.MCP_TOOL_CALL to blockingInteractionFactory
            ),
            emitter = emitter,
            registry = registry,
            messageIdProvider = SequenceMessageIdProvider()
        )

        withTimeout(250) {
            processor.process(
                commandRequestEnvelope(
                    inboundMessageId = "in-1",
                    interactionId = "int-1",
                    commandType = WorkerProtocolCommandTypes.MCP_TOOL_CALL
                )
            )
        }

        runCurrent()
        assertTrue(blockingInteractionFactory.startObserved.isCompleted)
        assertNotNull(registry.get("int-1"))

        blockingInteractionFactory.allowCompletion.complete(Unit)
        runCurrent()
        assertEquals(null, registry.get("int-1"))
        assertEquals(0, emitter.messages.size)
    }

    /**
     * Builds a minimal command request envelope for tests.
     *
     * @param inboundMessageId Inbound protocol message identifier.
     * @param interactionId Logical interaction identifier.
     * @param commandType Command type used for session-factory lookup.
     * @return Protocol envelope carrying the command request payload.
     */
    private fun commandRequestEnvelope(
        inboundMessageId: String,
        interactionId: String,
        commandType: String
    ): WorkerProtocolMessage {
        return WorkerProtocolMessage(
            id = inboundMessageId,
            type = WorkerProtocolMessageTypes.COMMAND_REQUEST,
            interactionId = interactionId,
            payload = buildJsonObject {
                put("commandType", commandType)
                put("data", JsonObject(emptyMap()))
            }
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
     * Session factory that creates sessions which wait on a completion gate.
     */
    private class BlockingInteractionFactory : WorkerInteractionFactory {
        /**
         * Deferred completed when a created session starts execution.
         */
        val startObserved: CompletableDeferred<Unit> = CompletableDeferred()

        /**
         * Deferred that controls when created sessions are allowed to complete.
         */
        val allowCompletion: CompletableDeferred<Unit> = CompletableDeferred()

        /**
         * @param envelope Original inbound `command.request` envelope.
         * @param requestPayload Decoded request payload.
         * @param emitter Outbound protocol emitter used by the created session.
         * @return New blocking test session.
         */
        override fun create(
            envelope: WorkerProtocolMessage,
            requestPayload: WorkerCommandRequestPayload,
            emitter: WorkerOutboundMessageEmitter
        ): WorkerActiveInteraction {
            return BlockingInteraction(
                interactionId = envelope.interactionId,
                startObserved = startObserved,
                allowCompletion = allowCompletion
            )
        }
    }

    /**
     * Active test session that blocks until completion is explicitly released.
     *
     * @property interactionId Stable interaction identifier used by the registry.
     * @property startObserved Deferred completed when the session starts.
     * @property allowCompletion Deferred awaited before completion.
     */
    private class BlockingInteraction(
        override val interactionId: String,
        private val startObserved: CompletableDeferred<Unit>,
        private val allowCompletion: CompletableDeferred<Unit>
    ) : WorkerActiveInteraction {
        /**
         * Marks the session as started and waits for release.
         */
        override suspend fun start() {
            startObserved.complete(Unit)
            allowCompletion.await()
        }

        /**
         * Ignores follow-up protocol envelopes because this helper session only validates launch behavior.
         *
         * @param message Inbound protocol envelope addressed to this interaction session.
         */
        override suspend fun onMessage(message: WorkerProtocolMessage) {
            // No-op in this test helper.
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


