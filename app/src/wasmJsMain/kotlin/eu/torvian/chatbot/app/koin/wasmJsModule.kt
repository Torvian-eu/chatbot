package eu.torvian.chatbot.app.koin

import eu.torvian.chatbot.app.config.AppConfiguration
import eu.torvian.chatbot.app.service.auth.BrowserTokenStorage
import eu.torvian.chatbot.app.service.auth.TokenStorage
import eu.torvian.chatbot.app.service.api.ktor.BrowserWebSocketAuthSubprotocolProvider
import eu.torvian.chatbot.app.service.api.ktor.WebSocketAuthSubprotocolProvider
import eu.torvian.chatbot.app.service.clipboard.ClipboardService
import eu.torvian.chatbot.app.service.clipboard.ClipboardServiceWasmJs
import eu.torvian.chatbot.app.service.security.BrowserCertificateStorage
import eu.torvian.chatbot.app.service.security.CertificateStorage
import eu.torvian.chatbot.common.security.CryptoProvider
import eu.torvian.chatbot.common.security.WasmJsWebCryptoProvider
import org.koin.dsl.module

/**
 * WASM/JS-specific Koin module.
 *
 * This module provides dependencies specific to the WASM/JS platform,
 * using the secure Web Crypto API for cryptographic operations.
 *
 * @param config The application configuration.
 * @return A Koin module with WASM/JS-specific dependencies.
 */
fun wasmJsModule(config: AppConfiguration) = module {
    single<CryptoProvider> {
        // Use the secure Web Crypto API provider for WASM/JS.
        WasmJsWebCryptoProvider(config.encryption)
    }

    single<TokenStorage> {
        BrowserTokenStorage(
            cryptoProvider = get(),
            storageNamespace = "${config.storage.baseApplicationPath}/${config.storage.dataDir}/${config.storage.tokenStorageDir}"
        )
    }

    single<WebSocketAuthSubprotocolProvider> {
        BrowserWebSocketAuthSubprotocolProvider(tokenStorage = get())
    }

    single<CertificateStorage> {
        BrowserCertificateStorage()
    }

    single<ClipboardService> {
        ClipboardServiceWasmJs()
    }
}
