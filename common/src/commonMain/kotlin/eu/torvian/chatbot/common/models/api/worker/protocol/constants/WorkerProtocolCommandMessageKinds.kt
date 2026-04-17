package eu.torvian.chatbot.common.models.api.worker.protocol.constants

/**
 * Stable message-kind constants carried by [WorkerCommandMessagePayload].
 */
object WorkerProtocolCommandMessageKinds {
    /**
     * Message kind that indicates command execution may proceed.
     */
    const val PROCEED = "proceed"

    /**
     * Message kind used when a command session asks for additional input.
     */
    const val INPUT_REQUEST = "input.request"

    /**
     * Message kind used when a peer responds to a previous input request.
     */
    const val INPUT_RESPONSE = "input.response"

    /**
     * Message kind used when a command session asks for explicit approval.
     */
    const val APPROVAL_REQUEST = "approval.request"

    /**
     * Message kind used when a peer answers an approval request.
     */
    const val APPROVAL_RESPONSE = "approval.response"
}

