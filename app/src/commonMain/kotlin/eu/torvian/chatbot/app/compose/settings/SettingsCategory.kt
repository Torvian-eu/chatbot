package eu.torvian.chatbot.app.compose.settings

/**
 * Top-level settings categories shown in the settings shell.
 */
enum class SettingsCategory {
    Providers,
    Models,
    ModelSettings,
    AgentRoles,
    McpServers,
    Workers,
    BuiltInTools,
    E2EASecurity,
    Appearance,
    About
}

/**
 * Human-readable label used for the sidebar and breadcrumb trail.
 */
val SettingsCategory.displayLabel: String
    get() = when (this) {
        SettingsCategory.Providers -> "Providers"
        SettingsCategory.Models -> "Models"
        SettingsCategory.ModelSettings -> "Model Settings"
        SettingsCategory.AgentRoles -> "Agent Roles"
        SettingsCategory.McpServers -> "MCP Servers"
        SettingsCategory.Workers -> "Workers"
        SettingsCategory.BuiltInTools -> "Built-in Tools"
        SettingsCategory.E2EASecurity -> "E2EA Security"
        SettingsCategory.Appearance -> "Appearance"
        SettingsCategory.About -> "About"
    }
