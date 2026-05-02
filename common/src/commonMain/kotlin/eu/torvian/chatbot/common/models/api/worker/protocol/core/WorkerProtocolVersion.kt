package eu.torvian.chatbot.common.models.api.worker.protocol.core

/**
 * Version metadata for the worker WebSocket protocol envelope.
 *
 * [CURRENT] marks the version this build emits by default, while [SUPPORTED]
 * defines the inbound versions this build can still parse.
 */
object WorkerProtocolVersion {
    /**
     * Version written into newly produced envelopes.
     */
    const val CURRENT: Int = 1

    /**
     * Versions accepted when decoding inbound envelopes.
     *
     * Keeping this separate from [CURRENT] allows future rolling upgrades where
     * sender and receiver may temporarily run different versions.
     */
    val SUPPORTED: Set<Int> = setOf(CURRENT)
}