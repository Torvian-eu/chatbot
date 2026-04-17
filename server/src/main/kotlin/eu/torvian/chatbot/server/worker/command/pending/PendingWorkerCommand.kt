package eu.torvian.chatbot.server.worker.command.pending

import eu.torvian.chatbot.server.worker.command.WorkerCommandDispatchResult
import kotlinx.coroutines.CompletableDeferred

/**
 * Pending worker command metadata.
 *
 * The registry stores a completion handle so inbound lifecycle messages can resume the suspended
 * dispatch call once the worker reports a terminal outcome.
 *
 * @property workerId Worker identifier that owns the live session.
 * @property interactionId Correlation identifier shared by the command lifecycle.
 * @property messageId Transport message identifier used for the outbound request frame.
 * @property commandType Domain command type carried by the request payload.
 * @property completion Deferred result completed by inbound lifecycle processing.
 */
data class PendingWorkerCommand(
    val workerId: Long,
    val interactionId: String,
    val messageId: String,
    val commandType: String,
    val completion: CompletableDeferred<WorkerCommandDispatchResult> = CompletableDeferred()
)