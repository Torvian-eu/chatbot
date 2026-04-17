package eu.torvian.chatbot.common.models.api.worker.protocol.constants

/**
 * Stable reason codes used in `command.rejected` payloads.
 */
object WorkerProtocolRejectionReasons {
    /**
     * Rejection code used when the envelope type is not recognized.
     */
    const val UNSUPPORTED_MESSAGE_TYPE = "unsupported_message_type"

    /**
     * Rejection code used when a required payload is absent.
     */
    const val MISSING_PAYLOAD = "missing_payload"

    /**
     * Rejection code used when a required field is missing from a payload.
     */
    const val MISSING_FIELD = "missing_field"

    /**
     * Rejection code used when a payload field has the wrong shape or value.
     */
    const val INVALID_FIELD = "invalid_field"

    /**
     * Rejection code used when a command-request payload cannot be decoded.
     */
    const val INVALID_COMMAND_PAYLOAD = "invalid_command_payload"

    /**
     * Rejection code used when the requested command type is unsupported.
     */
    const val UNSUPPORTED_COMMAND_TYPE = "unsupported_command_type"

    /**
     * Rejection code used when a recognized command type is not implemented in the worker.
     */
    const val NOT_IMPLEMENTED = "not_implemented"

    /**
     * Rejection code used when an interaction ID is already active in the worker runtime.
     */
    const val DUPLICATE_INTERACTION_ID = "duplicate_interaction_id"

    /**
     * Rejection code used when a follow-up command message targets no active interaction.
     */
    const val UNKNOWN_INTERACTION_ID = "unknown_interaction_id"

    /**
     * Rejection code used when the peer speaks an unsupported protocol version.
     */
    const val UNSUPPORTED_PROTOCOL_VERSION = "unsupported_protocol_version"
}