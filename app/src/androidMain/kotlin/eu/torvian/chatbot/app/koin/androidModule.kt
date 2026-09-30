package eu.torvian.chatbot.app.koin

import eu.torvian.chatbot.app.config.AppConfiguration
import eu.torvian.chatbot.app.service.auth.DeviceIdentityStorage
import eu.torvian.chatbot.app.service.auth.FileSystemDeviceIdentityStorage
import eu.torvian.chatbot.app.service.auth.FileSystemTokenStorage
import eu.torvian.chatbot.app.service.auth.TokenStorage
import eu.torvian.chatbot.app.service.clipboard.ClipboardService
import eu.torvian.chatbot.app.service.clipboard.ClipboardServiceAndroid
import eu.torvian.chatbot.app.service.security.CertificateStorage
import eu.torvian.chatbot.app.service.security.FileSystemCertificateStorage
import eu.torvian.chatbot.app.service.turnnotification.AndroidAppFocusFeeder
import eu.torvian.chatbot.app.service.turnnotification.TurnAlertSoundPlayer
import eu.torvian.chatbot.app.service.turnnotification.TurnAlertSoundPlayerAndroid
import eu.torvian.chatbot.app.service.turnnotification.TurnOsNotificationService
import eu.torvian.chatbot.app.service.turnnotification.TurnOsNotificationServiceAndroid
import eu.torvian.chatbot.common.security.AsymmetricCryptoProvider
import eu.torvian.chatbot.common.security.CryptoProvider
import eu.torvian.chatbot.common.security.EncryptionService
import eu.torvian.chatbot.common.security.JvmAsymmetricCryptoProvider
import eu.torvian.chatbot.common.security.AESCryptoProvider
import kotlinx.io.files.Path
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/**
 * Android-specific Koin module.
 *
 * This module provides dependencies specific to the Android platform.
 *
 * @param config The application configuration containing all settings.
 * @return A Koin module with Android-specific dependencies.
 */
fun androidModule(config: AppConfiguration) = module {
    single<CryptoProvider> {
        AESCryptoProvider(config.encryption)
    }

    single<EncryptionService> {
        EncryptionService(get())
    }

    single<AsymmetricCryptoProvider> {
        JvmAsymmetricCryptoProvider()
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

    single<DeviceIdentityStorage> {
        FileSystemDeviceIdentityStorage(
            storageDirectoryPath = Path(
                config.storage.baseApplicationPath,
                config.storage.dataDir,
                config.storage.deviceIdentityStorageDir
            ).toString()
        )
    }

    single<ClipboardService> {
        ClipboardServiceAndroid(androidContext())
    }

    single<TurnAlertSoundPlayer> {
        TurnAlertSoundPlayerAndroid(androidContext())
    }

    single<TurnOsNotificationService> {
        TurnOsNotificationServiceAndroid(androidContext())
    }

    // Eager so the lifecycle registration happens at app start, before the first activity resumes.
    single(createdAtStart = true) {
        AndroidAppFocusFeeder(androidContext(), get())
    }
}
