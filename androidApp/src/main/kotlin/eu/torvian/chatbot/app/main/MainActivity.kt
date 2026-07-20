package eu.torvian.chatbot.app.main

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import eu.torvian.chatbot.app.compose.startup.CommonAppLifecycleManager
import eu.torvian.chatbot.app.config.FileSystemClientConfigLoader
import eu.torvian.chatbot.app.koin.androidModule
import eu.torvian.chatbot.app.koin.appModule
import eu.torvian.chatbot.app.utils.misc.createKmpLogger
import org.koin.android.ext.koin.androidContext

private val logger = createKmpLogger("AndroidMainActivity")

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Config files live in the app's private files directory, e.g.:
        // /data/data/eu.torvian.chatbot/files/config/
        val configDir = "${applicationContext.filesDir.absolutePath}/config"

        setContent {
            CommonAppLifecycleManager(
                configDir = configDir,
                configLoader = FileSystemClientConfigLoader(),
                onExit = {
                    logger.info("User requested application exit from error screen.")
                    finish()
                },
                koinApp = { config ->
                    androidContext(this@MainActivity)
                    modules(
                        androidModule(config),
                        appModule(config)
                    )
                }
            )
        }
    }
}