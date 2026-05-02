package eu.torvian.chatbot.app.compose.settings

/**
 * Top-level settings categories shown in the settings shell.
 */
enum class SettingsCategory {
    Providers,
    Models,
    ModelSettings,
    McpServers,
    Workers
}

/**
 * Human-readable label used for the sidebar and breadcrumb trail.
 */
val SettingsCategory.displayLabel: String
    get() = when (this) {
        SettingsCategory.Providers -> "Providers"
        SettingsCategory.Models -> "Models"
        SettingsCategory.ModelSettings -> "Model Settings"
        SettingsCategory.McpServers -> "MCP Servers"
        SettingsCategory.Workers -> "Workers"
    }
