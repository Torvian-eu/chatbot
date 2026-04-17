package eu.torvian.chatbot.worker.protocol.handshake

import eu.torvian.chatbot.worker.protocol.transport.WorkerOutboundMessageEmitter
import eu.torvian.chatbot.worker.protocol.ids.UuidWorkerMessageIdProvider
import eu.torvian.chatbot.worker.protocol.ids.WorkerInteractionIdProvider
import eu.torvian.chatbot.worker.protocol.ids.WorkerMessageIdProvider
import eu.torvian.chatbot.worker.protocol.registry.WorkerActiveInteractionRegistry
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
class WorkerSessionHelloStarter(
    private val interactionScope: CoroutineScope,
    private val registry: WorkerActiveInteractionRegistry,
    private val interactionIdProvider: WorkerInteractionIdProvider,
    private val emitter: WorkerOutboundMessageEmitter,
    private val handshakeStateStore: WorkerSessionHandshakeStateStore,
    private val messageIdProvider: WorkerMessageIdProvider = UuidWorkerMessageIdProvider()
) {
    companion object {
        /**
         * Logger used to report unexpected handshake runtime failures.
         */
        private val logger: Logger = LogManager.getLogger(WorkerSessionHelloStarter::class.java)
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
    ): WorkerSessionHelloStartResult {
        val interactionId = interactionIdProvider.nextInteractionId()
        val interaction = WorkerSessionHelloInteraction(
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
            return WorkerSessionHelloStartResult.NotStarted(
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

        return WorkerSessionHelloStartResult.Started(interactionId)
    }
}

/**
 * Result of attempting to start a worker-side hello interaction.
 */
sealed interface WorkerSessionHelloStartResult {
    /**
     * Successful start result.
     *
     * @property interactionId Interaction identifier of the launched handshake.
     */
    data class Started(
        val interactionId: String
    ) : WorkerSessionHelloStartResult

    /**
     * Failed start result with a logical reason.
     *
     * @property interactionId Generated interaction identifier that failed registration.
     * @property reason Human-readable reason explaining why start failed.
     */
    data class NotStarted(
        val interactionId: String,
        val reason: String
    ) : WorkerSessionHelloStartResult
}
