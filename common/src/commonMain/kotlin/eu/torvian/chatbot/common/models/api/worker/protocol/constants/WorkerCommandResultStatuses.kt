package eu.torvian.chatbot.common.models.api.worker.protocol.constants

/**
 * Final command execution status values used inside `command.result` payloads.
 */
object WorkerCommandResultStatuses {
    /**
     * Result status value indicating successful command completion.
     */
    const val SUCCESS = "success"

    /**
     * Result status value indicating failed command completion.
     */
    const val ERROR = "error"
}