package eu.torvian.chatbot.worker.protocol.routing

import eu.torvian.chatbot.common.models.api.worker.protocol.core.WorkerProtocolMessage

/**
 * Processes one inbound worker protocol envelope.
 */
fun interface IncomingMessageProcessor {
    /**
     * Processes a single inbound envelope.
     *
     * @param message Inbound worker protocol envelope.
     */
    suspend fun process(message: WorkerProtocolMessage)
}

