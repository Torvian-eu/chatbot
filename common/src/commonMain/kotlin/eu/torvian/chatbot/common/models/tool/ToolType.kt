package eu.torvian.chatbot.common.models.tool

import kotlinx.serialization.Serializable

/**
 * Enumeration of supported tool types.
 *
 * Each tool type corresponds to a specific executor implementation that handles
 * the execution logic for that category of tools.
 */
@Serializable
enum class ToolType {
    /** Local MCP (Model Context Protocol) tools running via STDIO */
    MCP_LOCAL,

    /** Remote MCP tools accessible via HTTP/SSE transport (future) */
    MCP_REMOTE,

    /**
     * Built-in tools that are dispatched directly to a worker over the `tool.call` protocol.
     *
     * The worker resolves the unprefixed built-in tool name against its in-memory registry
     * and executes the matching implementation inside its `workspace` directory. Tool-name
     * prefixing is a server-side catalog concern and is not applied at the worker runtime.
     */
    BUILTIN_WORKER,

    /**
     * Operator-executed tools: the server relays the tool call to the operator (in v1 the client
     * app itself), who runs the tool and returns the result over the chat WebSocket.
     *
     * Unlike [BUILTIN_WORKER] tools there is no worker dispatch and therefore no on-device
     * signature; the operator is the same principal that drives the chat socket. Each operator
     * tool is a per-user instance (see [OperatorToolDefinition]), so approval preferences and
     * enable/disable stay user-scoped.
     */
    OPERATOR,

    /**
     * Server built-in tools: cataloged, per-user instances that are executed entirely in-process on
     * the server inside the chat turn, with no worker dispatch and no operator relay.
     *
     * Each tool is a per-user instance (see [ServerBuiltInToolDefinition]) seeded from
     * [ServerBuiltInToolCatalog]. After a plain (non-signed) approval the server executes the
     * matching handler directly against its user-scoped services, so approval preferences and
     * enable/disable stay user-scoped like operator tools.
     */
    BUILTIN_SERVER
}
