package eu.torvian.chatbot.server.worker.session

import java.util.concurrent.ConcurrentHashMap
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * Thread-safe in-memory registry for currently connected worker sessions.
 *
 * The registry is intentionally ephemeral: it provides fast lookup and reconnect replacement
 * semantics for live command dispatch without persisting socket state.
 */
class InMemoryWorkerSessionRegistry : WorkerSessionRegistry {
    private val sessionsByWorkerId: ConcurrentHashMap<Long, ConnectedWorkerSession> = ConcurrentHashMap()

    override fun register(session: ConnectedWorkerSession): ConnectedWorkerSession? {

        val previous = sessionsByWorkerId.put(session.workerContext.workerId, session)
        if (previous != null && previous !== session) {
            logger.info(
                "Replacing existing worker session in registry (workerId={}, workerUid={})",
                session.workerContext.workerId,
                session.workerContext.workerUid
            )
        } else {
            logger.debug(
                "Registered worker session in registry (workerId={}, workerUid={})",
                session.workerContext.workerId,
                session.workerContext.workerUid
            )
        }

        return previous
    }

    override fun get(workerId: Long): ConnectedWorkerSession? = sessionsByWorkerId[workerId]

    override fun remove(workerId: Long, session: ConnectedWorkerSession?): Boolean {
        val removed = if (session == null) {
            sessionsByWorkerId.remove(workerId) != null
        } else {
            sessionsByWorkerId.remove(workerId, session)
        }

        if (removed) {
            logger.debug("Removed worker session from registry (workerId={})", workerId)
        }

        return removed
    }

    companion object {
        /**
         * Logger used for registry lifecycle diagnostics.
         */
        private val logger: Logger = LogManager.getLogger(InMemoryWorkerSessionRegistry::class.java)
    }
}

