package eu.torvian.chatbot.worker.protocol.routing

import arrow.core.getOrElse
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCommandRejectedPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCommandRequestPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.constants.WorkerProtocolCommandTypes
import eu.torvian.chatbot.common.models.api.worker.protocol.core.WorkerProtocolMessage
import eu.torvian.chatbot.common.models.api.worker.protocol.constants.WorkerProtocolMessageTypes
import eu.torvian.chatbot.common.models.api.worker.protocol.constants.WorkerProtocolRejectionReasons
import eu.torvian.chatbot.common.models.api.worker.protocol.codec.decodeProtocolPayload
import eu.torvian.chatbot.worker.protocol.transport.OutboundMessageEmitter
import eu.torvian.chatbot.worker.protocol.factory.InteractionFactory
import eu.torvian.chatbot.worker.protocol.ids.MessageIdProvider
import eu.torvian.chatbot.worker.protocol.interaction.Interaction
import eu.torvian.chatbot.worker.protocol.registry.InMemoryInteractionRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * Unit tests for [CommandRequestProcessor].
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CommandRequestProcessorTest {
    /**
     * Verifies that MCP server-control command types are recognized and delegated to factories.
     */
    @Test
    fun `mcp server control command types are routed to registered interactions`() = runTest {
        val emitter = RecordingEmitter()
        val registry = InMemoryInteractionRegistry()
        val observedCommandTypes: MutableList<String> = mutableListOf()
        val observedCommandTypesMutex = Mutex()
        val recordingFactory = InteractionFactory { envelope, requestPayload, _ ->
            RecordingInteraction(
                interactionId = envelope.interactionId,
                onStart = {
                    observedCommandTypesMutex.withLock {
                        observedCommandTypes += requestPayload.commandType
                    }
                }
            )
        }

        val processor = CommandRequestProcessor(
            interactionScope = this,
            interactionFactoriesByCommandType = mapOf(
                WorkerProtocolCommandTypes.MCP_SERVER_START to recordingFactory,
                WorkerProtocolCommandTypes.MCP_SERVER_STOP to recordingFactory,
                WorkerProtocolCommandTypes.MCP_SERVER_TEST_CONNECTION to recordingFactory,
                WorkerProtocolCommandTypes.MCP_SERVER_DISCOVER_TOOLS to recordingFactory
            ),
            emitter = emitter,
            registry = registry,
            messageIdProvider = SequenceMessageIdProvider()
        )

        val supportedCommandTypes = listOf(
            WorkerProtocolCommandTypes.MCP_SERVER_START,
            WorkerProtocolCommandTypes.MCP_SERVER_STOP,
            WorkerProtocolCommandTypes.MCP_SERVER_TEST_CONNECTION,
            WorkerProtocolCommandTypes.MCP_SERVER_DISCOVER_TOOLS
        )

        supportedCommandTypes.forEachIndexed { index, commandType ->
            processor.process(
                commandRequestEnvelope(
                    inboundMessageId = "in-route-$index",
                    interactionId = "int-route-$index",
                    commandType = commandType
                )
            )
        }

        runCurrent()

        assertEquals(0, emitter.messages.size)
        assertEquals(supportedCommandTypes.sorted(), observedCommandTypes.sorted())
    }

    /**
     * Verifies unsupported command types are rejected with a stable reason code.
     */
    @Test
    fun `unsupported command type is rejected`() = runTest {
        val emitter = RecordingEmitter()
        val registry = InMemoryInteractionRegistry()
        val processor = CommandRequestProcessor(
            interactionScope = this,
            interactionFactoriesByCommandType = emptyMap(),
            emitter = emitter,
            registry = registry,
            messageIdProvider = SequenceMessageIdProvider()
        )

        processor.process(
            commandRequestEnvelope(
                inboundMessageId = "in-unsupported",
                interactionId = "int-unsupported",
                commandType = "mcp.server.unsupported"
            )
        )

        assertEquals(1, emitter.messages.size)
        val rejected = decodeProtocolPayload<WorkerCommandRejectedPayload>(
            emitter.messages.single().payload!!,
            "WorkerCommandRejectedPayload"
        ).getOrElse { error("Expected rejection payload to decode: $it") }
        assertEquals(WorkerProtocolRejectionReasons.UNSUPPORTED_COMMAND_TYPE, rejected.reasonCode)
    }

    /**
     * Verifies that missing request payloads are rejected immediately.
     */
    @Test
    fun `missing payload is rejected`() = runTest {
        val emitter = RecordingEmitter()
        val registry = InMemoryInteractionRegistry()
        val processor = CommandRequestProcessor(
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
        val registry = InMemoryInteractionRegistry()
        val blockingInteractionFactory = BlockingInteractionFactory()
        val processor = CommandRequestProcessor(
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
        val registry = InMemoryInteractionRegistry()
        val blockingInteractionFactory = BlockingInteractionFactory()
        val processor = CommandRequestProcessor(
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
    private class RecordingEmitter : OutboundMessageEmitter {
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
    private class BlockingInteractionFactory : InteractionFactory {
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
            emitter: OutboundMessageEmitter
        ): Interaction {
            return BlockingInteraction(
                interactionId = envelope.interactionId,
                startObserved = startObserved,
                allowCompletion = allowCompletion
            )
        }
    }

    /**
     * Minimal [Interaction] implementation used by command routing assertions.
     *
     * @property interactionId Stable interaction identifier tracked by the processor.
     * @property onStart Callback invoked when the interaction starts.
     */
    private class RecordingInteraction(
        override val interactionId: String,
        private val onStart: suspend () -> Unit
    ) : Interaction {
        /**
         * Executes the configured start callback.
         */
        override suspend fun start() {
            onStart()
        }

        /**
         * Ignores follow-up envelopes for this test helper.
         *
         * @param message Inbound protocol envelope addressed to this interaction.
         */
        override suspend fun onMessage(message: WorkerProtocolMessage) {
            // No-op in this test helper.
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
    ) : Interaction {
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
    private class SequenceMessageIdProvider : MessageIdProvider {
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


