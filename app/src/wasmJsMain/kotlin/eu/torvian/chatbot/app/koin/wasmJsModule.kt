package eu.torvian.chatbot.app.koin

import eu.torvian.chatbot.app.main.AppConfig
import eu.torvian.chatbot.app.service.auth.FileSystemTokenStorage
import eu.torvian.chatbot.app.service.auth.TokenStorage
import eu.torvian.chatbot.common.security.CryptoProvider
import eu.torvian.chatbot.common.security.EncryptionConfig
import eu.torvian.chatbot.common.security.WasmJsWebCryptoProvider
import kotlinx.io.files.Path
import org.koin.dsl.module

/**
 * WASM/JS-specific Koin module.
 *
 * This module provides dependencies specific to the WASM/JS platform,
 * using the secure Web Crypto API for cryptographic operations.
 *
 * @param appConfig The application configuration containing platform-specific paths.
 * @param encryptionConfig The encryption configuration to use for secure storage.
 * @return A Koin module with WASM/JS-specific dependencies.
 */
fun wasmJsModule(appConfig: AppConfig, encryptionConfig: EncryptionConfig) = module {
    single<CryptoProvider> {
        // Use the secure Web Crypto API provider for WASM/JS.
        WasmJsWebCryptoProvider(encryptionConfig)
    }

    single<TokenStorage> {
        FileSystemTokenStorage(
            cryptoProvider = get(),
            storageDirectoryPath = Path(appConfig.baseUserDataStoragePath, appConfig.tokenStorageDir).toString()
        )
    }
}