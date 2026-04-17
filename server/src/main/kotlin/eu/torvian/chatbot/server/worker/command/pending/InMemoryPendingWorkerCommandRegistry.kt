package eu.torvian.chatbot.server.worker.command.pending

import eu.torvian.chatbot.server.worker.command.WorkerCommandDispatchResult
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe in-memory registry of pending worker command interactions.
 */
class InMemoryPendingWorkerCommandRegistry : PendingWorkerCommandRegistry {
    private val commandsByInteractionId = ConcurrentHashMap<String, PendingWorkerCommand>()
    private val interactionIdsByWorkerId = ConcurrentHashMap<Long, MutableSet<String>>()

    override fun register(pendingCommand: PendingWorkerCommand): Boolean {
        val previous = commandsByInteractionId.putIfAbsent(pendingCommand.interactionId, pendingCommand)
        if (previous != null) {
            logger.debug(
                "Rejected duplicate pending worker command interaction (workerId={}, interactionId={}, commandType={})",
                pendingCommand.workerId,
                pendingCommand.interactionId,
                pendingCommand.commandType
            )
            return false
        }

        interactionIdsByWorkerId
            .computeIfAbsent(pendingCommand.workerId) { ConcurrentHashMap.newKeySet() }
            .add(pendingCommand.interactionId)

        logger.debug(
            "Registered pending worker command interaction (workerId={}, interactionId={}, commandType={})",
            pendingCommand.workerId,
            pendingCommand.interactionId,
            pendingCommand.commandType
        )
        return true
    }

    override fun get(interactionId: String): PendingWorkerCommand? = commandsByInteractionId[interactionId]

    override fun markAccepted(interactionId: String): PendingWorkerCommand? = commandsByInteractionId[interactionId]

    override fun complete(interactionId: String, outcome: WorkerCommandDispatchResult): Boolean {
        val pendingCommand = commandsByInteractionId.remove(interactionId) ?: return false
        removeFromWorkerIndex(pendingCommand.workerId, interactionId)
        val completed = pendingCommand.completion.complete(outcome)
        if (completed) {
            logger.debug(
                "Completed pending worker command interaction (workerId={}, interactionId={}, commandType={}, outcome={})",
                pendingCommand.workerId,
                pendingCommand.interactionId,
                pendingCommand.commandType,
                outcome::class.simpleName
            )
        }
        return completed
    }

    override fun remove(interactionId: String): Boolean {
        val pendingCommand = commandsByInteractionId.remove(interactionId) ?: return false
        removeFromWorkerIndex(pendingCommand.workerId, interactionId)
        logger.debug(
            "Removed pending worker command interaction without completion (workerId={}, interactionId={}, commandType={})",
            pendingCommand.workerId,
            pendingCommand.interactionId,
            pendingCommand.commandType
        )
        return true
    }

    override fun failAllForWorker(workerId: Long, reason: String): Int {
        val interactionIds = interactionIdsByWorkerId[workerId]?.toList().orEmpty()
        var completedCount = 0

        for (interactionId in interactionIds) {
            val pendingCommand = commandsByInteractionId[interactionId] ?: continue
            val outcome = WorkerCommandDispatchResult.SessionDisconnected(
                workerId = pendingCommand.workerId,
                interactionId = pendingCommand.interactionId,
                commandType = pendingCommand.commandType,
                reason = reason
            )
            if (complete(interactionId, outcome)) {
                completedCount += 1
            }
        }

        if (completedCount > 0) {
            logger.info(
                "Completed pending worker commands after disconnect (workerId={}, count={}, outcome={})",
                workerId,
                completedCount,
                reason
            )
        }

        return completedCount
    }

    /**
     * Removes an interaction identifier from the worker index and clears the worker bucket when it
     * becomes empty.
     *
     * @param workerId Worker identifier that owns the pending command.
     * @param interactionId Interaction identifier to remove from the worker bucket.
     */
    private fun removeFromWorkerIndex(workerId: Long, interactionId: String) {
        val workerInteractionIds = interactionIdsByWorkerId[workerId] ?: return
        workerInteractionIds.remove(interactionId)
        if (workerInteractionIds.isEmpty()) {
            interactionIdsByWorkerId.remove(workerId, workerInteractionIds)
        }
    }

    companion object {
        /**
         * Logger used for pending command registry diagnostics.
         */
        private val logger: Logger = LogManager.getLogger(InMemoryPendingWorkerCommandRegistry::class.java)
    }
}