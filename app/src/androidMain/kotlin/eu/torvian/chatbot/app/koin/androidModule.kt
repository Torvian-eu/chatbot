package eu.torvian.chatbot.app.koin

import eu.torvian.chatbot.app.main.AppConfig
import eu.torvian.chatbot.app.service.auth.TokenStorage
import eu.torvian.chatbot.app.service.auth.FileSystemTokenStorage
import eu.torvian.chatbot.common.security.AESCryptoProvider
import eu.torvian.chatbot.common.security.CryptoProvider
import eu.torvian.chatbot.common.security.EncryptionConfig
import kotlinx.io.files.Path
import org.koin.dsl.module

/**
 * Android-specific Koin module.
 *
 * This module provides dependencies specific to the Android platform.
 *
 * @param appConfig The application configuration.
 * @param encryptionConfig The encryption configuration to use for secure storage.
 * @return A Koin module with Android-specific dependencies.
 */
fun androidModule(appConfig: AppConfig, encryptionConfig: EncryptionConfig) = module {
    single<CryptoProvider> {
        AESCryptoProvider(encryptionConfig)
    }

    single<TokenStorage> {
        FileSystemTokenStorage(
            cryptoProvider = get(),
            storageDirectoryPath = Path(appConfig.baseUserDataStoragePath, appConfig.tokenStorageDir).toString()
        )
    }
}