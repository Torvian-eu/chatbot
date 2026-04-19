package eu.torvian.chatbot.server.worker.command

import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCommandResultPayload

/**
 * Successful outcome produced by the worker command dispatch layer.
 *
 * @property workerId Worker identifier that received the command.
 * @property interactionId Correlation identifier shared by all lifecycle messages.
 * @property commandType Domain command type that was dispatched.
 * @property result Worker-reported final result payload
 */
data class WorkerCommandDispatchSuccess(
    val workerId: Long,
    val interactionId: String,
    val commandType: String,
    val result: WorkerCommandResultPayload
)

