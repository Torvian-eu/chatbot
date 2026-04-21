package eu.torvian.chatbot.worker.protocol.handshake

import eu.torvian.chatbot.worker.protocol.transport.OutboundMessageEmitter
import eu.torvian.chatbot.worker.protocol.ids.UuidMessageIdProvider
import eu.torvian.chatbot.worker.protocol.ids.InteractionIdProvider
import eu.torvian.chatbot.worker.protocol.ids.MessageIdProvider
import eu.torvian.chatbot.worker.protocol.registry.InteractionRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * Starts worker-side hello interactions and wires them into the active-interaction registry.
 *
 * @property interactionScope Coroutine scope used to run handshake interactions asynchronously.
 * @property registry Active-interaction registry that tracks running handshakes.
 * @property interactionIdProvider Provider used to generate new interaction identifiers.
 * @property emitter Outbound protocol emitter used by created interactions.
 * @property handshakeStateStore Handshake-state recorder used by created interactions.
 * @property messageIdProvider Message-ID provider used by created interactions.
 */
class HelloStarter(
    private val interactionScope: CoroutineScope,
    private val registry: InteractionRegistry,
    private val interactionIdProvider: InteractionIdProvider,
    private val emitter: OutboundMessageEmitter,
    private val handshakeStateStore: SessionHandshakeStateStore,
    private val messageIdProvider: MessageIdProvider = UuidMessageIdProvider()
) {
    companion object {
        /**
         * Logger used to report unexpected handshake runtime failures.
         */
        private val logger: Logger = LogManager.getLogger(HelloStarter::class.java)
    }

    /**
     * Creates, registers, and launches a `session.hello` interaction.
     *
     * @param workerUid Worker UID to include in hello payload and welcome validation.
     * @param capabilities Capability identifiers announced to the server.
     * @param supportedProtocolVersions Protocol versions accepted by the worker.
     * @param workerVersion Optional worker build/version metadata.
     * @return Start result describing whether the interaction was launched.
     */
    fun start(
        workerUid: String,
        capabilities: List<String>,
        supportedProtocolVersions: List<Int>,
        workerVersion: String? = null
    ): HelloStartResult {
        val interactionId = interactionIdProvider.nextInteractionId()
        val interaction = HelloInteraction(
            interactionId = interactionId,
            workerUid = workerUid,
            capabilities = capabilities,
            supportedProtocolVersions = supportedProtocolVersions,
            workerVersion = workerVersion,
            emitter = emitter,
            registry = registry,
            handshakeStateStore = handshakeStateStore,
            messageIdProvider = messageIdProvider
        )

        if (!registry.register(interaction)) {
            return HelloStartResult.NotStarted(
                interactionId = interactionId,
                reason = "An interaction with id '$interactionId' is already active"
            )
        }

        interactionScope.launch {
            try {
                interaction.start()
            } catch (_: CancellationException) {
                registry.remove(interactionId)
            } catch (error: Exception) {
                logger.error(
                    "Session hello interaction failed unexpectedly (interactionId={})",
                    interactionId,
                    error
                )
                handshakeStateStore.markFailed(
                    interactionId = interactionId,
                    reason = "Session hello interaction failed unexpectedly: ${error.message ?: error::class.simpleName}"
                )
                registry.remove(interactionId)
            }
        }

        return HelloStartResult.Started(interactionId)
    }
}

