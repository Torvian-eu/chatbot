package eu.torvian.chatbot.app.koin

import eu.torvian.chatbot.app.database.DriverFactory
import eu.torvian.chatbot.app.database.DriverFactoryDesktop
import eu.torvian.chatbot.app.main.AppConfig
import eu.torvian.chatbot.app.service.auth.FileSystemTokenStorage
import eu.torvian.chatbot.app.service.auth.TokenStorage
import eu.torvian.chatbot.app.service.clipboard.ClipboardService
import eu.torvian.chatbot.app.service.clipboard.ClipboardServiceDesktop
import eu.torvian.chatbot.app.service.mcp.LocalMCPServerProcessManager
import eu.torvian.chatbot.app.service.mcp.LocalMCPServerProcessManagerDesktop
import eu.torvian.chatbot.app.service.mcp.LocalMCPServerManager
import eu.torvian.chatbot.app.service.mcp.LocalMCPServerManagerImpl
import eu.torvian.chatbot.app.service.mcp.LocalMCPToolCallMediator
import eu.torvian.chatbot.app.service.mcp.LocalMCPToolCallMediatorImpl
import eu.torvian.chatbot.app.service.mcp.MCPClientService
import eu.torvian.chatbot.app.service.mcp.MCPClientServiceImpl
import eu.torvian.chatbot.app.service.security.CertificateStorage
import eu.torvian.chatbot.app.service.security.FileSystemCertificateStorage
import eu.torvian.chatbot.app.viewmodel.LocalMCPServerViewModel
import eu.torvian.chatbot.common.security.AESCryptoProvider
import eu.torvian.chatbot.common.security.CryptoProvider
import eu.torvian.chatbot.common.security.EncryptionConfig
import eu.torvian.chatbot.common.security.EncryptionService
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import org.koin.dsl.onClose

/**
 * Desktop-specific Koin module.
 *
 * This module provides dependencies specific to the desktop platform.
 *
 * @param appConfig The application configuration containing platform-specific paths.
 * @param encryptionConfig The encryption configuration to use for secure storage.
 * @return A Koin module with desktop-specific dependencies.
 */
fun desktopModule(appConfig: AppConfig, encryptionConfig: EncryptionConfig) = module {
    single<CryptoProvider> {
        AESCryptoProvider(encryptionConfig)
    }

    single<EncryptionService> {
        EncryptionService(get())
    }

    single<TokenStorage> {
        FileSystemTokenStorage(
            cryptoProvider = get(),
            storageDirectoryPath = Path(appConfig.baseUserDataStoragePath, appConfig.tokenStorageDir).toString()
        )
    }

    single<CertificateStorage> {
        FileSystemCertificateStorage(
            storageDirectoryPath = Path(appConfig.baseUserDataStoragePath, appConfig.certificateStorageDir).toString()
        )
    }

    single<ClipboardService> {
        ClipboardServiceDesktop()
    }

    single<DriverFactory> {
        val databasePath = Path(appConfig.baseUserDataStoragePath, "local.db").toString()
        DriverFactoryDesktop(databasePath = databasePath)
    }

    single<LocalMCPServerProcessManager> {
        LocalMCPServerProcessManagerDesktop(
            clock = get()
        )
    }.onClose { manager ->
        runBlocking { manager?.close() }
    }

    single<MCPClientService> {
        MCPClientServiceImpl(
            processManager = get()
        )
    }.onClose { service ->
        runBlocking { service?.close() }
    }

    single<LocalMCPServerManager> {
        LocalMCPServerManagerImpl(
            serverRepository = get(),
            toolRepository = get(),
            mcpClientService = get(),
            clock = get()
        )
    }

    single<LocalMCPToolCallMediator> {
        LocalMCPToolCallMediatorImpl(get(), get())
    }

    // ViewModels specific to desktop (require LocalMCPServerManager)
    viewModel {
        LocalMCPServerViewModel(
            serverManager = get(),
            mcpToolRepository = get(),
            toolRepository = get(),
            notificationService = get()
        )
    }
}
