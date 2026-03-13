package eu.torvian.chatbot.app.koin

import eu.torvian.chatbot.app.config.AppConfiguration
import eu.torvian.chatbot.app.database.DriverFactory
import eu.torvian.chatbot.app.database.DriverFactoryDesktop
import eu.torvian.chatbot.app.service.auth.FileSystemTokenStorage
import eu.torvian.chatbot.app.service.auth.TokenStorage
import eu.torvian.chatbot.app.service.clipboard.ClipboardService
import eu.torvian.chatbot.app.service.clipboard.ClipboardServiceDesktop
import eu.torvian.chatbot.app.service.mcp.*
import eu.torvian.chatbot.app.service.security.CertificateStorage
import eu.torvian.chatbot.app.service.security.FileSystemCertificateStorage
import eu.torvian.chatbot.app.viewmodel.LocalMCPServerViewModel
import eu.torvian.chatbot.common.security.AESCryptoProvider
import eu.torvian.chatbot.common.security.CryptoProvider
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
 * @param config The application configuration containing all settings.
 * @return A Koin module with desktop-specific dependencies.
 */
fun desktopModule(config: AppConfiguration) = module {
    single<CryptoProvider> {
        AESCryptoProvider(config.encryption)
    }

    single<EncryptionService> {
        EncryptionService(get())
    }

    single<TokenStorage> {
        FileSystemTokenStorage(
            cryptoProvider = get(),
            storageDirectoryPath = Path(
                config.storage.baseApplicationPath,
                config.storage.dataDir,
                config.storage.tokenStorageDir
            ).toString()
        )
    }

    single<CertificateStorage> {
        FileSystemCertificateStorage(
            storageDirectoryPath = Path(
                config.storage.baseApplicationPath,
                config.storage.dataDir,
                config.storage.certificateStorageDir
            ).toString()
        )
    }

    single<ClipboardService> {
        ClipboardServiceDesktop()
    }

    single<DriverFactory> {
        val databasePath = Path(config.storage.baseApplicationPath, config.storage.dataDir, "local.db").toString()
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
