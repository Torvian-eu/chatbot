package eu.torvian.chatbot.app.compose.settings

import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.domain.models.LocalMCPServer
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.service.mcp.LocalMCPServerOverview
import eu.torvian.chatbot.app.viewmodel.LocalMCPServerDialogState
import eu.torvian.chatbot.app.viewmodel.LocalMCPServerFormState
import eu.torvian.chatbot.app.viewmodel.LocalMCPServerOperation
import eu.torvian.chatbot.app.viewmodel.LocalMCPToolFormState
import eu.torvian.chatbot.common.models.tool.LocalMCPToolDefinition
import eu.torvian.chatbot.common.models.tool.UserToolApprovalPreference

/**
 * UI state for the MCP Servers tab.
 */
data class LocalMCPServersTabState(
    val serverOverviews: DataState<RepositoryError, List<LocalMCPServerOverview>>,
    val selectedServerOverview: LocalMCPServerOverview?,
    val selectedServerToolApprovalPreferences: DataState<RepositoryError, List<UserToolApprovalPreference>>,
    val dialogState: LocalMCPServerDialogState,
    val operationInProgress: LocalMCPServerOperation?
)

/**
 * Actions available in the MCP Servers tab.
 */
interface LocalMCPServersTabActions {
    fun onLoadServers(userId: Long)
    fun onSelectServer(serverId: Long?)
    fun onStartAddingNewServer()
    fun onStartEditingServer(server: LocalMCPServer)
    fun onStartDeletingServer(server: LocalMCPServer)
    fun onCancelDialog()
    fun onUpdateServerForm(update: (LocalMCPServerFormState) -> LocalMCPServerFormState)
    fun onSaveServer()
    fun onDeleteServer(serverId: Long)
    fun onTestConnection(serverId: Long)
    fun onRefreshTools(serverId: Long)
    fun onStartServer(serverId: Long)
    fun onStopServer(serverId: Long)
    fun onToggleServerEnabled(server: LocalMCPServer)
    fun onToggleToolEnabled(tool: LocalMCPToolDefinition)
    fun onStartEditingTool(tool: LocalMCPToolDefinition)
    fun onEnableAllTools(serverId: Long)
    fun onDisableAllTools(serverId: Long)
    fun onUpdateToolForm(update: (LocalMCPToolFormState) -> LocalMCPToolFormState)
    fun onSaveTool()
    fun onDeleteToolApprovalPreference(toolDefinitionId: Long)
}
