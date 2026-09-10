package eu.torvian.chatbot.app.compose.settings

/**
 * Top-level settings categories shown in the settings shell.
 */
enum class SettingsCategory {
    Providers,
    Models,
    ModelSettings,
    ModelPresets,
    AgentRoles,
    Projects,
    McpServers,
    Workers,
    BuiltInTools,
    OperatorTools,
    ServerBuiltInTools,
    E2EASecurity,
    ConversationCompaction,
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
        SettingsCategory.ModelPresets -> "Model Presets"
        SettingsCategory.AgentRoles -> "Agent Roles"
        SettingsCategory.Projects -> "Projects"
        SettingsCategory.McpServers -> "MCP Servers"
        SettingsCategory.Workers -> "Workers"
        SettingsCategory.BuiltInTools -> "Built-in Tools"
        SettingsCategory.OperatorTools -> "Operator Tools"
        SettingsCategory.ServerBuiltInTools -> "Server Built-In Tools"
        SettingsCategory.E2EASecurity -> "E2EA Security"
        SettingsCategory.ConversationCompaction -> "Conversation Compaction"
        SettingsCategory.Appearance -> "Appearance"
        SettingsCategory.About -> "About"
    }
