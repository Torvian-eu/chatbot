package eu.torvian.chatbot.server.worker.command

import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCommandRejectedPayload
import kotlin.time.Duration

/**
 * Terminal failure outcome produced by the worker command dispatch layer.
 *
 * This error model captures lifecycle failures and local dispatch failures while leaving
 * successful completion data in [WorkerCommandDispatchSuccess].
 */
sealed interface WorkerCommandDispatchError {
    /**
     * Indicates that the worker rejected the command.
     *
     * @property workerId Worker identifier that received the command.
     * @property interactionId Correlation identifier shared by all lifecycle messages.
     * @property commandType Domain command type that was dispatched.
     * @property rejection Worker-reported rejection payload.
     */
    data class Rejected(
        val workerId: Long,
        val interactionId: String,
        val commandType: String,
        val rejection: WorkerCommandRejectedPayload
    ) : WorkerCommandDispatchError

    /**
     * Indicates that the worker acknowledged the request but no terminal outcome arrived in time.
     *
     * @property workerId Worker identifier that received the command.
     * @property interactionId Correlation identifier shared by all lifecycle messages.
     * @property commandType Domain command type that was dispatched.
     * @property timeout Maximum wait time that expired.
     */
    data class TimedOut(
        val workerId: Long,
        val interactionId: String,
        val commandType: String,
        val timeout: Duration
    ) : WorkerCommandDispatchError

    /**
     * Indicates that the worker was not connected when dispatch was attempted.
     *
     * @property workerId Worker identifier that could not be resolved to an active live session.
     */
    data class WorkerNotConnected(
        val workerId: Long
    ) : WorkerCommandDispatchError

    /**
     * Indicates that the live session disappeared before the command could complete.
     *
     * @property workerId Worker identifier whose session disconnected.
     * @property interactionId Correlation identifier shared by all lifecycle messages.
     * @property commandType Domain command type that was dispatched.
     * @property reason Human-readable disconnect reason, when available.
     */
    data class SessionDisconnected(
        val workerId: Long,
        val interactionId: String,
        val commandType: String,
        val reason: String? = null
    ) : WorkerCommandDispatchError

    /**
     * Indicates that the command could not be written to the live socket.
     *
     * @property workerId Worker identifier that was targeted.
     * @property interactionId Correlation identifier shared by all lifecycle messages.
     * @property commandType Domain command type that was dispatched.
     * @property reason Human-readable transport failure reason.
     */
    data class SendFailed(
        val workerId: Long,
        val interactionId: String,
        val commandType: String,
        val reason: String
    ) : WorkerCommandDispatchError

    /**
     * Indicates that the worker emitted a malformed command lifecycle frame for a pending command.
     *
     * @property workerId Worker identifier that emitted the malformed frame.
     * @property interactionId Correlation identifier shared by all lifecycle messages.
     * @property commandType Domain command type that was dispatched.
     * @property messageType Inbound lifecycle message type that failed to decode.
     * @property reason Human-readable decode failure reason.
     */
    data class MalformedLifecyclePayload(
        val workerId: Long,
        val interactionId: String,
        val commandType: String,
        val messageType: String,
        val reason: String
    ) : WorkerCommandDispatchError

    /**
     * Indicates that the server attempted to register an already-used interaction identifier.
     *
     * @property interactionId Duplicate interaction identifier.
     */
    data class DuplicateInteractionId(
        val interactionId: String
    ) : WorkerCommandDispatchError
}

