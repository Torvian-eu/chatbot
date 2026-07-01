package eu.torvian.chatbot.server.koin

import eu.torvian.chatbot.server.ktor.routes.ApiRoutesKtor
import eu.torvian.chatbot.server.worker.command.DefaultWorkerCommandDispatchService
import eu.torvian.chatbot.server.worker.command.WorkerCommandDispatchService
import eu.torvian.chatbot.server.worker.command.pending.InMemoryPendingWorkerCommandRegistry
import eu.torvian.chatbot.server.worker.command.pending.PendingWorkerCommandRegistry
import eu.torvian.chatbot.server.worker.protocol.codec.WorkerServerWebSocketMessageCodec
import eu.torvian.chatbot.server.worker.protocol.handshake.WorkerSessionHelloHandler
import eu.torvian.chatbot.server.worker.protocol.routing.WorkerServerIncomingMessageRouter
import eu.torvian.chatbot.server.worker.session.InMemoryWorkerSessionRegistry
import eu.torvian.chatbot.server.worker.session.WorkerSessionRegistry
import eu.torvian.chatbot.server.utils.transactions.ExposedTransactionScope
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import org.koin.dsl.module

/**
 * Koin module for providing miscellaneous dependencies.
 *
 * This module includes:
 * - An instance of [TransactionScope] using [ExposedTransactionScope].
 * - An instance of [ApiRoutesKtor] for configuring Ktor routes.
 *
 * @return A Koin module with miscellaneous dependencies.
 */
fun miscModule() = module {
    single<TransactionScope> {
        ExposedTransactionScope(get())
    }
    single<WorkerSessionRegistry> { InMemoryWorkerSessionRegistry() }
    single<PendingWorkerCommandRegistry> { InMemoryPendingWorkerCommandRegistry() }
    single<WorkerCommandDispatchService> { DefaultWorkerCommandDispatchService(get(), get()) }
    single { WorkerServerWebSocketMessageCodec() }
    single { WorkerSessionHelloHandler() }
    single { WorkerServerIncomingMessageRouter(get(), get()) }
    single {
        ApiRoutesKtor(
            sessionService = get(),
            groupService = get(),
            llmProviderService = get(),
            llmModelService = get(),
            modelSettingsService = get(),
            messageService = get(),
            searchService = get(),
            chatService = get(),
            toolService = get(),
            toolCallService = get(),
            localMCPServerService = get(),
            localMCPRuntimeControlService = get(),
            localMCPServerConfigSyncService = get(),
            localMCPToolDefinitionService = get(),
            authenticationService = get(),
            tokenService = get(),
            accountManagementService = get(),
            deviceTrustService = get(),
            securityAuditService = get(),
            userService = get(),
            userGroupService = get(),
            roleService = get(),
            authorizationService = get(),
            workerService = get(),
            json = get(),
            appConfig = get(),
            userPreferenceService = get()
        )
    }
}