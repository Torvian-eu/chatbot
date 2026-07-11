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
    BUILTIN_WORKER
}
