package eu.torvian.chatbot.common.models.agent

import kotlinx.serialization.Serializable

/**
 * Identifies which principal drives operator-executed tools.
 *
 * Operator tools (e.g. `spawn_agent`) are relayed by the server to an "operator" that executes them
 * and reports the result back over the chat WebSocket. In v1 the operator is always the client app
 * ([CLIENT_APP]); the future background agent operator is a worker-like service that must be
 * registered as a trusted signer before it may drive worker tool calls inside spawned conversations.
 */
@Serializable
enum class OperatorType {

    /** The client application the user is chatting from. */
    CLIENT_APP,

    /** A dedicated background agent-operator service (future). */
    AGENT_OPERATOR
}
