package eu.torvian.chatbot.app.main

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import eu.torvian.chatbot.app.compose.AppShell
import eu.torvian.chatbot.app.koin.androidModule
import eu.torvian.chatbot.app.koin.appModule
import eu.torvian.chatbot.app.koin.databaseModule
import eu.torvian.chatbot.common.security.EncryptionConfig
import org.koin.compose.KoinApplication

class MainActivity : ComponentActivity() {

    // TODO: Read config objects from configuration file (config.json)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val appConfig = AppConfig(
            serverUrl = "https://localhost:8443",
//            serverUrl = "https://10.0.2.2:8443",  // For emulator?
            baseUserDataStoragePath = applicationContext.filesDir.absolutePath,
            tokenStorageDir = "tokens",
            certificateStorageDir = "certs"
        )

        val encryptionConfig = EncryptionConfig(
            // TODO: **IMPORTANT:** Change this key in production!
            masterKeys = mapOf(1 to "G2CgJOQQtIC+yfz+LLoDp/osBLUVzW9JE9BrQA0dQFo="),
            keyVersion = 1
        )

        setContent {
            KoinApplication(application = {
                modules(
                    androidModule(appConfig, encryptionConfig),
                    databaseModule,
                    appModule(appConfig)
                )
            }) {
                AppShell()
            }
        }
    }
}