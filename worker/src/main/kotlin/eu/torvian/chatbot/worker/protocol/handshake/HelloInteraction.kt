package eu.torvian.chatbot.worker.protocol.handshake

import arrow.core.getOrElse
import eu.torvian.chatbot.common.models.api.worker.protocol.core.WorkerProtocolMessage
import eu.torvian.chatbot.common.models.api.worker.protocol.constants.WorkerProtocolMessageTypes
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerSessionHelloPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerSessionWelcomePayload
import eu.torvian.chatbot.common.models.api.worker.protocol.codec.decodeProtocolPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.builders.sessionHello
import eu.torvian.chatbot.worker.protocol.transport.OutboundMessageEmitter
import eu.torvian.chatbot.worker.protocol.ids.UuidMessageIdProvider
import eu.torvian.chatbot.worker.protocol.ids.MessageIdProvider
import eu.torvian.chatbot.worker.protocol.interaction.ChannelBackedInteraction
import eu.torvian.chatbot.worker.protocol.registry.InteractionRegistry

/**
 * Active worker-side interaction that performs one `session.hello -> session.welcome` handshake lifecycle.
 *
 * @property interactionId Stable handshake interaction identifier shared by hello and welcome envelopes.
 * @property workerUid Expected worker UID used for outbound hello payloads and inbound welcome validation.
 * @property capabilities Capability identifiers announced to the server in `session.hello`.
 * @property supportedProtocolVersions Protocol versions accepted by this worker.
 * @property workerVersion Optional worker build/version metadata sent in hello.
 * @property emitter Outbound emitter used to send `session.hello`.
 * @property registry Active-interaction registry used for lifecycle cleanup.
 * @property handshakeStateStore Handshake-state recorder used to persist success/failure outcome.
 * @property messageIdProvider Message-ID provider used for outbound hello envelopes.
 */
class HelloInteraction(
    interactionId: String,
    private val workerUid: String,
    private val capabilities: List<String>,
    private val supportedProtocolVersions: List<Int>,
    private val workerVersion: String?,
    emitter: OutboundMessageEmitter,
    private val registry: InteractionRegistry,
    private val handshakeStateStore: SessionHandshakeStateStore,
    private val messageIdProvider: MessageIdProvider = UuidMessageIdProvider()
) : ChannelBackedInteraction(
    interactionId = interactionId,
    emitter = emitter
) {
    /**
     * Message identifier of the emitted `session.hello` envelope for `replyTo` validation.
     */
    private var helloMessageId: String? = null

    /**
     * Emits `session.hello` and waits until a valid `session.welcome` completes this interaction.
     */
    override suspend fun start() {
        handshakeStateStore.markPending(interactionId)

        val helloId = messageIdProvider.nextMessageId()
        helloMessageId = helloId
        emitter.emit(
            sessionHello(
                id = helloId,
                interactionId = interactionId,
                payload = WorkerSessionHelloPayload(
                    workerUid = workerUid,
                    capabilities = capabilities,
                    supportedProtocolVersions = supportedProtocolVersions,
                    workerVersion = workerVersion
                )
            )
        )

        while (true) {
            val inboundMessage = awaitNextMessage()
            if (inboundMessage.type != WorkerProtocolMessageTypes.SESSION_WELCOME) {
                // Ignore unrelated message types addressed to this interaction.
                continue
            }

            handleWelcomeMessage(inboundMessage)
            return
        }
    }

    /**
     * Validates one inbound `session.welcome` envelope and finalizes the interaction.
     *
     * @param message Inbound welcome envelope.
     */
    private fun handleWelcomeMessage(message: WorkerProtocolMessage) {
        val welcomePayload = message.payload?.let { payload ->
            decodeProtocolPayload<WorkerSessionWelcomePayload>(
                payload = payload,
                targetType = "WorkerSessionWelcomePayload"
            ).getOrElse {
                failInteraction("Unable to decode session.welcome payload: $it")
                return
            }
        } ?: run {
            failInteraction("session.welcome payload is missing")
            return
        }

        val expectedReplyTo = helloMessageId
        if (expectedReplyTo != null && message.replyTo != expectedReplyTo) {
            failInteraction(
                "session.welcome replyTo mismatch: expected '$expectedReplyTo', received '${message.replyTo}'"
            )
            return
        }

        if (welcomePayload.workerUid != workerUid) {
            failInteraction(
                "session.welcome workerUid mismatch: expected '$workerUid', received '${welcomePayload.workerUid}'"
            )
            return
        }

        if (welcomePayload.selectedProtocolVersion !in supportedProtocolVersions) {
            failInteraction(
                "session.welcome selected unsupported protocol version ${welcomePayload.selectedProtocolVersion}"
            )
            return
        }

        handshakeStateStore.markSucceeded(
            interactionId = interactionId,
            welcome = SessionWelcomeState(
                workerUid = welcomePayload.workerUid,
                selectedProtocolVersion = welcomePayload.selectedProtocolVersion,
                acceptedCapabilities = welcomePayload.acceptedCapabilities
            )
        )
        registry.remove(interactionId)
    }

    /**
     * Records a logical handshake failure and unregisters this active interaction.
     *
     * @param reason Human-readable reason explaining why the interaction failed.
     */
    private fun failInteraction(reason: String) {
        handshakeStateStore.markFailed(interactionId, reason)
        registry.remove(interactionId)
    }
}
