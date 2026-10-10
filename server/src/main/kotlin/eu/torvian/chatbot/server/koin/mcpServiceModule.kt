package eu.torvian.chatbot.server.koin

import eu.torvian.chatbot.server.service.core.*
import eu.torvian.chatbot.server.service.core.impl.*
import eu.torvian.chatbot.server.service.mcp.LocalMCPExecutor
import eu.torvian.chatbot.server.worker.mcp.configsync.DefaultLocalMCPServerConfigSyncService
import eu.torvian.chatbot.server.worker.mcp.configsync.DefaultLocalMCPServerWorkerSyncService
import eu.torvian.chatbot.server.worker.mcp.configsync.LocalMCPServerConfigSyncService
import eu.torvian.chatbot.server.worker.mcp.configsync.LocalMCPServerWorkerSyncService
import eu.torvian.chatbot.server.worker.mcp.runtimecontrol.DefaultLocalMCPRuntimeCommandDispatchService
import eu.torvian.chatbot.server.worker.mcp.runtimecontrol.DefaultLocalMCPRuntimeControlService
import eu.torvian.chatbot.server.worker.mcp.runtimecontrol.LocalMCPRuntimeCommandDispatchService
import eu.torvian.chatbot.server.worker.mcp.runtimecontrol.LocalMCPRuntimeControlService
import eu.torvian.chatbot.server.worker.mcp.toolcall.DefaultLocalMCPToolCallDispatchService
import eu.torvian.chatbot.server.worker.mcp.toolcall.LocalMCPToolCallDispatchService
import org.koin.dsl.module

/**
 * Dependency injection module for local MCP servers.
 *
 * Provides local MCP server management, runtime control and command dispatch, configuration and
 * worker synchronization, tool definition lookup, tool call dispatch and the executor that talks to
 * a worker-managed MCP process.
 */
fun mcpServiceModule() = module {
    single<LocalMCPServerService> { LocalMCPServerServiceImpl(get(), get(), get(), get(), get(), get(), get(), get()) }
    single<LocalMCPRuntimeCommandDispatchService> { DefaultLocalMCPRuntimeCommandDispatchService(get()) }
    single<LocalMCPServerWorkerSyncService> { DefaultLocalMCPServerWorkerSyncService(get()) }
    single<LocalMCPRuntimeControlService> { DefaultLocalMCPRuntimeControlService(get(), get(), get()) }
    single<LocalMCPServerConfigSyncService> { DefaultLocalMCPServerConfigSyncService(get(), get()) }
    single<LocalMCPToolDefinitionService> { LocalMCPToolDefinitionServiceImpl(get(), get(), get(), get(), get()) }
    single<LocalMCPToolCallDispatchService> { DefaultLocalMCPToolCallDispatchService(get()) }
    single<LocalMCPExecutor> { LocalMCPExecutor(get(), get()) }
}
