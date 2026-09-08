package eu.torvian.chatbot.common.models.agent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Shared execution mode for operator tools that drive a chat turn and report a result back.
 *
 * Both `spawn_agent` and `send_message` expose this mode to the calling LLM through their input
 * schema, and the mode value travels unchanged inside the operator relay payloads
 * ([AgentSpawnRequest], [SendMessageRequest]) — so the wire values below are simultaneously
 * LLM-facing schema values and relay DTO values and must always stay aligned. The default for both
 * tools is [WAIT_FOR_RESPONSE].
 *
 * The two values are:
 *  - [WAIT_FOR_RESPONSE]: the operator blocks until the driven turn completes and returns the
 *    target session's last assistant message (for `spawn_agent`, the spawned session's summary).
 *  - [FIRE_AND_FORGET]: the operator starts the driven turn asynchronously and returns immediately
 *    (a success notification or the spawned session id), leaving the turn running in the background
 *    of the target session's own lifecycle — the executor never awaits it and never cancels it.
 */
@Serializable
enum class OperatorToolMode {

    /**
     * Block until the driven turn completes, then return its last assistant message.
     */
    @SerialName("wait_for_response")
    WAIT_FOR_RESPONSE,

    /**
     * Start the driven turn in the background and return immediately without awaiting it.
     */
    @SerialName("fire_and_forget")
    FIRE_AND_FORGET
}