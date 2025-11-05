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
    /** Web search tools (e.g., DuckDuckGo, Google) */
    WEB_SEARCH,

    /** Mathematical calculation tools */
    CALCULATOR,

    /** Weather information tools */
    WEATHER,

    /** Custom or user-defined tools */
    CUSTOM
}

