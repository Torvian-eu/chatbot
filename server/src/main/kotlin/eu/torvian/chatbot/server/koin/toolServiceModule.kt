package eu.torvian.chatbot.server.koin

import eu.torvian.chatbot.common.models.tool.ToolNameSanitizer
import eu.torvian.chatbot.common.models.tool.ToolNameValidator
import eu.torvian.chatbot.common.models.tool.ToolNamePrefixValidator
import eu.torvian.chatbot.server.service.builtin.BuiltInWorkerToolExecutor
import eu.torvian.chatbot.server.service.builtin.DefaultBuiltInWorkerToolExecutor
import eu.torvian.chatbot.server.service.builtin.DefaultOperatorToolExecutor
import eu.torvian.chatbot.server.service.builtin.DefaultServerBuiltInToolExecutor
import eu.torvian.chatbot.server.service.builtin.OperatorToolExecutor
import eu.torvian.chatbot.server.service.builtin.ServerBuiltInTool
import eu.torvian.chatbot.server.service.builtin.ServerBuiltInToolExecutor
import eu.torvian.chatbot.server.service.builtin.tools.CloneProjectTool
import eu.torvian.chatbot.server.service.builtin.tools.CreateAgentRoleTool
import eu.torvian.chatbot.server.service.builtin.tools.CreateInstructionTool
import eu.torvian.chatbot.server.service.builtin.tools.CreateModelPresetTool
import eu.torvian.chatbot.server.service.builtin.tools.CreateProjectTool
import eu.torvian.chatbot.server.service.builtin.tools.DeleteAgentRoleTool
import eu.torvian.chatbot.server.service.builtin.tools.DeleteInstructionTool
import eu.torvian.chatbot.server.service.builtin.tools.DeleteModelPresetTool
import eu.torvian.chatbot.server.service.builtin.tools.DeleteProjectTool
import eu.torvian.chatbot.server.service.builtin.tools.EditInstructionTool
import eu.torvian.chatbot.server.service.builtin.tools.GetCurrentSessionInfoTool
import eu.torvian.chatbot.server.service.builtin.tools.ListAgentRolesTool
import eu.torvian.chatbot.server.service.builtin.tools.ListInstructionsTool
import eu.torvian.chatbot.server.service.builtin.tools.ListModelPresetsTool
import eu.torvian.chatbot.server.service.builtin.tools.ListModelSettingsTool
import eu.torvian.chatbot.server.service.builtin.tools.ListModelsTool
import eu.torvian.chatbot.server.service.builtin.tools.ListProjectsTool
import eu.torvian.chatbot.server.service.builtin.tools.ListToolsTool
import eu.torvian.chatbot.server.service.builtin.tools.ReadAgentRoleTool
import eu.torvian.chatbot.server.service.builtin.tools.ReadInstructionTool
import eu.torvian.chatbot.server.service.builtin.tools.ReadModelPresetTool
import eu.torvian.chatbot.server.service.builtin.tools.ReadProjectTool
import eu.torvian.chatbot.server.service.builtin.tools.ReadToolTool
import eu.torvian.chatbot.server.service.builtin.tools.UpdateAgentRoleTool
import eu.torvian.chatbot.server.service.builtin.tools.UpdateModelPresetTool
import eu.torvian.chatbot.server.service.builtin.tools.UpdateProjectTool
import eu.torvian.chatbot.server.service.core.*
import eu.torvian.chatbot.server.service.core.agent.AgentSpawnRequestBuilder
import eu.torvian.chatbot.server.service.core.agent.DefaultAgentSpawnRequestBuilder
import eu.torvian.chatbot.server.service.core.agent.DefaultSendMessageRequestBuilder
import eu.torvian.chatbot.server.service.core.agent.SendMessageRequestBuilder
import eu.torvian.chatbot.server.service.core.impl.*
import eu.torvian.chatbot.server.service.core.toolcall.DefaultToolCallOrchestrator
import eu.torvian.chatbot.server.service.core.toolcall.ToolCallOrchestrator
import eu.torvian.chatbot.server.worker.builtin.BuiltInToolDispatchService
import eu.torvian.chatbot.server.worker.builtin.DefaultBuiltInToolDispatchService
import org.koin.dsl.module

/**
 * Dependency injection module for tool management and execution.
 *
 * Provides tool-name validation, the tool catalog and call orchestration, the built-in worker tool
 * dispatch and definition services, the server-relayed operator tools, and the server built-in tool
 * registry, executor and definition services.
 */
fun toolServiceModule() = module {
    // --- Tool-name sanitization/validation (LLM-safe character set) ---
    single { ToolNameSanitizer() }
    single { ToolNameValidator() }
    single { ToolNamePrefixValidator() }

    single<ToolCallOrchestrator> {
        DefaultToolCallOrchestrator(get(), get(), get(), get(), get())
    }

    single<ToolService> { ToolServiceImpl(get(), get(), get(), get(), get()) }
    single<ToolCallService> { ToolCallServiceImpl(get(), get()) }

    // --- Built-in worker tool services (direct `tool.call` dispatch) ---
    single<BuiltInToolDispatchService> { DefaultBuiltInToolDispatchService(get()) }
    single<BuiltInWorkerToolExecutor> { DefaultBuiltInWorkerToolExecutor(get()) }
    single<BuiltInToolDefinitionSeeder> { BuiltInToolDefinitionSeeder(get(), get(), get()) }
    single<BuiltInToolDefinitionService> {
        BuiltInToolDefinitionServiceImpl(
            workerDao = get(),
            builtInToolDefinitionDao = get(),
            builtInToolDefinitionSeeder = get(),
            toolService = get(),
            transactionScope = get()
        )
    }

    // --- Operator tool services (server-relayed, operator-executed) ---
    single<AgentSpawnRequestBuilder> { DefaultAgentSpawnRequestBuilder(get(), get()) }
    single<SendMessageRequestBuilder> { DefaultSendMessageRequestBuilder(get(), get()) }
    single<OperatorToolExecutor> { DefaultOperatorToolExecutor(get(), get(), get()) }
    single<OperatorToolDefinitionSeeder> { OperatorToolDefinitionSeeder(get(), get(), get()) }
    single<OperatorToolDefinitionService> {
        OperatorToolDefinitionServiceImpl(
            operatorToolDefinitionDao = get(),
            operatorToolDefinitionSeeder = get(),
            toolService = get(),
            transactionScope = get()
        )
    }

    // --- Server built-in tool services (executed in-process on the server) ---
    single<Map<String, ServerBuiltInTool>> {
        // Registry of server built-in tools, keyed by catalog name (the executor dispatch key).
        // Keep this in sync with ServerBuiltInToolCatalog; each tool receives its own user-scoped
        // services via constructor injection (mirrors workerModule's BuiltInTool registry).
        listOf(
            ListAgentRolesTool(agentRoleService = get(), json = get()),
            ReadAgentRoleTool(agentRoleService = get(), json = get()),
            CreateAgentRoleTool(agentRoleService = get()),
            UpdateAgentRoleTool(agentRoleService = get()),
            ListModelsTool(llmModelService = get(), json = get()),
            ListModelSettingsTool(llmModelService = get(), modelSettingsService = get(), json = get()),
            ListToolsTool(toolService = get(), json = get()),
            ReadToolTool(toolService = get(), json = get()),
            GetCurrentSessionInfoTool(agentRoleService = get(), json = get()),
            ListProjectsTool(projectService = get(), json = get()),
            ReadProjectTool(projectService = get(), json = get()),
            CreateProjectTool(projectService = get(), json = get()),
            UpdateProjectTool(projectService = get()),
            DeleteProjectTool(projectService = get()),
            CloneProjectTool(projectService = get(), json = get()),
            DeleteAgentRoleTool(agentRoleService = get()),
            ListModelPresetsTool(modelPresetService = get(), json = get()),
            ReadModelPresetTool(modelPresetService = get(), json = get()),
            CreateModelPresetTool(modelPresetService = get(), json = get()),
            UpdateModelPresetTool(modelPresetService = get()),
            DeleteModelPresetTool(modelPresetService = get()),
            ListInstructionsTool(instructionService = get(), agentRoleService = get(), json = get()),
            ReadInstructionTool(instructionService = get(), json = get()),
            CreateInstructionTool(instructionService = get(), json = get()),
            EditInstructionTool(instructionService = get()),
            DeleteInstructionTool(instructionService = get()),
        ).associateBy { it.name }
    }
    single<ServerBuiltInToolExecutor> {
        DefaultServerBuiltInToolExecutor(
            json = get(),
            tools = get()
        )
    }
    // Resolves the effective per-user prefix (global preference, else the hardcoded default). The
    // defaultPrefix constructor argument is intentionally omitted here: this binding is the single
    // swap point when a configurable server default (tools.builtInToolNamePrefix) lands later.
    single<ServerBuiltInToolNamePrefixResolver> {
        ServerBuiltInToolNamePrefixResolverImpl(
            userPreferenceDao = get()
        )
    }
    single<ServerBuiltInToolDefinitionSeeder> {
        ServerBuiltInToolDefinitionSeeder(get(), get(), get(), get())
    }
    single<ServerBuiltInToolDefinitionService> {
        ServerBuiltInToolDefinitionServiceImpl(
            serverBuiltInToolDefinitionDao = get(),
            serverBuiltInToolDefinitionSeeder = get(),
            toolService = get(),
            transactionScope = get()
        )
    }
    single<ServerBuiltInToolNamePrefixService> {
        ServerBuiltInToolNamePrefixServiceImpl(
            userPreferenceDao = get(),
            serverBuiltInToolDefinitionSeeder = get(),
            prefixResolver = get(),
            transactionScope = get()
        )
    }
}
