package eu.torvian.chatbot.worker.protocol.ids

/**
 * Supplies outbound worker protocol message identifiers.
 */
fun interface MessageIdProvider {
    /**
     * Produces the next unique identifier for an outbound protocol message.
     *
     * @return Non-blank message identifier.
     */
    fun nextMessageId(): String
}

